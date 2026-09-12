package com.akari.ppx.xp

import android.content.Context
import android.os.Bundle
import com.akari.ppx.BuildConfig.APPLICATION_ID
import com.akari.ppx.data.Const.TARGET_APP_ID
import com.akari.ppx.data.XPrefs
import com.akari.ppx.utils.HookRuntime
import com.akari.ppx.utils.Log
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

    @JvmStatic
    fun onLegacyLoadPackage(packageName: String, classLoader: ClassLoader) {
        HookRuntime.useLegacy()
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
        HookRuntime.useModern(module, apiVersion)
        handleLoadPackage(packageName, classLoader)
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
        hooks.forEach { hook ->
            runCatching {
                Log.d("Entry run hook ${hook.javaClass.name}")
                when (hook) {
                    is SwitchHook -> {
                        XPrefs<Boolean>(hook.key).check(true) {
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
    }
}
