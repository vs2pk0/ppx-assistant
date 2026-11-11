package com.akari.ppx.xp

import android.content.Context
import android.os.Bundle
import com.akari.ppx.BuildConfig.APPLICATION_ID
import com.akari.ppx.data.Const.SETTINGS_REVISION
import com.akari.ppx.data.Const.TARGET_APP_ID
import com.akari.ppx.data.XPrefs
import com.akari.ppx.data.AutomationAvailability
import com.akari.ppx.utils.HookRuntime
import com.akari.ppx.utils.Log
import com.akari.ppx.utils.LogStore
import com.akari.ppx.utils.RuntimeLog
import com.akari.ppx.utils.check
import com.akari.ppx.utils.hookBeforeMethod
import com.akari.ppx.utils.new
import com.akari.ppx.xp.Init.cl
import com.akari.ppx.xp.Init.mainActivityClass
import com.akari.ppx.xp.Init.safeModeApplicationClass
import com.akari.ppx.xp.hook.BaseHook
import com.akari.ppx.xp.hook.SwitchHook
import dalvik.system.DexFile
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

object ModuleEntryBridge {
    private val installedClassLoaders =
        Collections.newSetFromMap(ConcurrentHashMap<Int, Boolean>())
    private val installedHookLoaders =
        Collections.newSetFromMap(ConcurrentHashMap<Int, Boolean>())

    private val installedFeatures = ConcurrentHashMap.newKeySet<String>()
    fun isFeatureInstalled(key: String) = key in installedFeatures

    @Volatile var installedCount = 0
        private set
    @Volatile var failedCount = 0
        private set
    @Volatile var loadedSettingsRevision: String? = null
        private set

    @Volatile private var logBridgeReady = false

    @JvmStatic
    fun onLegacyLoadPackage(packageName: String, classLoader: ClassLoader) {
        HookRuntime.useLegacy()
        installHostLog(packageName)
        if (packageName == APPLICATION_ID) {
            LegacyHookStatusInit.init(classLoader)
            return
        }
        handleLoadPackage(packageName, classLoader)
    }

    @JvmStatic
    fun onModernModuleLoaded(module: Any, apiVersion: Int, processName: String, isSystemServer: Boolean) {
        HookRuntime.useModern(module, apiVersion)
        Log.i("Modern module loaded api=$apiVersion process=$processName system=$isSystemServer")
    }

    @JvmStatic
    fun onModernPackageLoaded(module: Any, apiVersion: Int, packageName: String, classLoader: ClassLoader) {
        installHostLog(packageName)
        HookRuntime.useModern(module, apiVersion)
        handleLoadPackage(packageName, classLoader)
    }

    /**
     * The hooked process has no module storage access, so its log lines are
     * buffered in memory and forwarded to the module provider. [installHostLog]
     * is idempotent, and passing a real context enables the forwarder.
     */
    private fun installHostLog(packageName: String, source: String = "") {
        val context = runCatching { Init.ctx }.getOrNull()
        RuntimeLog.install("host", null, context)
        if (context != null && !logBridgeReady) {
            logBridgeReady = true
            Log.i("Entry log bridge ready for $packageName via $source")
        }
    }

    private fun handleLoadPackage(packageName: String, classLoader: ClassLoader) {
        when (packageName) {
            TARGET_APP_ID -> installTargetHooks(packageName, classLoader)
        }
    }

    private fun installTargetHooks(packageName: String, classLoader: ClassLoader) {
        if (!installedClassLoaders.add(System.identityHashCode(classLoader))) {
            Log.d("Entry skip duplicated loader for $packageName")
            return
        }
        cl = classLoader
        Log.i("Entry loaded for $packageName")
        val hooks = arrayListOf<BaseHook>()
        fun ensureHooks(context: Context, source: String) {
            if (System.identityHashCode(classLoader) in installedHookLoaders) return
            runCatching {
                installHostLog(packageName, source)
                if (hooks.isEmpty()) {
                    Init(context)
                    val info = context.packageManager.getApplicationInfo(APPLICATION_ID, 0)
                    val dex = DexFile(info.sourceDir)
                    try {
                        dex.entries().asSequence()
                            .filter { it.startsWith(BaseHook::class.java.`package`!!.name) }
                            .map { Class.forName(it) }
                            .filter { !it.isInterface && BaseHook::class.java.isAssignableFrom(it) && it != SwitchHook::class.java }
                            .forEach { type ->
                                if (hooks.none { it.javaClass == type }) hooks += type.new() as BaseHook
                            }
                    } finally { dex.close() }
                }
                installHooksOnce(packageName, classLoader, hooks, source)
            }.onFailure(Log::e)
        }
        safeModeApplicationClass?.hookBeforeMethod("attachBaseContext", Context::class.java) { param ->
            ensureHooks(param.args[0] as Context, "attachBaseContext")
        }
        // Recover if the framework delivered package initialization after application attachment.
        mainActivityClass?.hookBeforeMethod("onCreate", Bundle::class.java) { param ->
            ensureHooks((param.thisObject as Context).applicationContext, "MainActivity.onCreate")
        }
        mainActivityClass?.hookBeforeMethod("onResume") { param ->
            ensureHooks((param.thisObject as Context).applicationContext, "MainActivity.onResume")
        }
    }

    /** Host app version, so reports can be matched against known-good builds. */
    private fun targetAppVersion(): String = runCatching {
        Init.ctx.packageManager.getPackageInfo(TARGET_APP_ID, 0).versionName ?: "?"
    }.getOrDefault("?")

    private fun installHooksOnce(
        packageName: String,
        classLoader: ClassLoader,
        hooks: List<BaseHook>,
        source: String
    ) {
        // An early activity callback must not permanently mark an empty scan as installed.
        if (hooks.isEmpty()) {
            Log.i("Entry defer empty hook scan from $source")
            return
        }
        if (!installedHookLoaders.add(System.identityHashCode(classLoader))) {
            Log.d("Entry skip duplicated hook install for $packageName from $source")
            return
        }
        Log.i("Entry install hooks from $source size=${hooks.size}")
        loadedSettingsRevision = XPrefs<String>(SETTINGS_REVISION)
        Log.i("Entry settings revision=${loadedSettingsRevision ?: "unavailable"}")
        hooks.forEach { hook ->
            if (!AutomationAvailability.isHookAvailable(hook.javaClass.name)) {
                Log.i("Entry skip unavailable automation ${hook.javaClass.name}")
                return@forEach
            }
            runCatching {
                Log.d("Entry run hook ${hook.javaClass.name}")
                when (hook) {
                    is SwitchHook -> {
                        val enabled = XPrefs<Boolean>(hook.key)
                        Log.i("Entry switch ${hook.key}=$enabled")
                        enabled.check(true) {
                            hook.onHook()
                            installedFeatures.add(hook.key)
                            installedCount++
                        }
                    }
                    else -> { hook.onHook(); installedCount++ }
                }
            }.onFailure {
                failedCount++
                Log.e("${hook.javaClass.name} init failed")
                Log.e(it)
            }
        }
        Log.i(
            "Entry hooks done installed=$installedCount failed=$failedCount" +
                    " features=${installedFeatures.size}"
        )
        // Health report: tells support exactly which configuration channel is
        // live, and whether the target version has any missing class lookups.
        Log.i("Entry settings health ${XPrefs.healthReport()}")
        // Proof that the fallback channel really works, not just that the file exists.
        runCatching {
            val probe = XPrefs.snapshotOnly(SETTINGS_REVISION, "") as? String ?: ""
            Log.i(
                "Entry snapshot probe revision=${probe.take(8).ifBlank { "none" }}" +
                        " match=${probe.isNotBlank() && probe == loadedSettingsRevision}"
            )
        }.onFailure(Log::e)
        Log.i("Entry targetApp=${targetAppVersion()}")
    }
}
