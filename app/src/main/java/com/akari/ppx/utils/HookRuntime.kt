package com.akari.ppx.utils

import android.content.SharedPreferences
import android.os.ParcelFileDescriptor
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XC_MethodReplacement
import de.robv.android.xposed.XposedBridge
import io.github.libxposed.api.XposedInterface
import java.lang.reflect.Constructor
import java.lang.reflect.Executable
import java.lang.reflect.Member
import java.lang.reflect.Method

internal enum class HookKind {
    BEFORE,
    AFTER,
    REPLACE
}

class HookParam internal constructor(
    val member: Member,
    thisObject: Any?,
    val args: Array<Any?>,
    private val outcome: HookOutcome,
    private val originalInvoker: (Array<Any?>) -> Any?
) {
    private val rawThisObject = thisObject

    val method: Member
        get() = member

    val thisObject: Any
        get() = rawThisObject!!

    val thisObjectOrNull: Any?
        get() = rawThisObject

    var result: Any?
        get() = outcome.result
        set(value) {
            outcome.setResult(value)
        }

    var throwable: Throwable?
        get() = outcome.throwable
        set(value) {
            outcome.setThrowable(value)
        }

    fun invokeOriginalMethod(): Any? = originalInvoker(args)
}

internal class HookOutcome(
    hasOutcome: Boolean = false,
    initialResult: Any? = null,
    initialThrowable: Throwable? = null
) {
    var hasOutcome = hasOutcome
        private set

    var result: Any? = initialResult
        private set

    var throwable: Throwable? = initialThrowable
        private set

    fun setResult(value: Any?) {
        hasOutcome = true
        throwable = null
        result = value
    }

    fun setThrowable(value: Throwable?) {
        hasOutcome = value != null
        throwable = value
        if (value != null) {
            result = null
        }
    }
}

internal interface HookBackend {
    fun hook(member: Member, kind: HookKind, callback: (HookParam) -> Any?): Any?

    fun hookAllMethods(
        clazz: Class<*>,
        methodName: String?,
        kind: HookKind,
        callback: (HookParam) -> Any?
    ): Set<Any>

    fun hookAllConstructors(
        clazz: Class<*>,
        kind: HookKind,
        callback: (HookParam) -> Any?
    ): Set<Any>
}

object HookRuntime {
    @Volatile
    private var backend: HookBackend? = null

    val isModern: Boolean
        get() = backend is ModernHookBackend

    @Volatile
    private var remotePreferences: SharedPreferences? = null

    fun useLegacy() {
        backend = LegacyHookBackend
        remotePreferences = null
    }

    /**
     * Switches to the libxposed backend.
     *
     * Everything here is a *typed* call into the framework API. LSPosed's
     * "Xposed API 调用保护" (api 102 `PROP_RT_API_PROTECTION`) renames the
     * libxposed classes/members inside the module dex and rewrites every
     * reference in it — including `XposedInterface::class.java` literals — but
     * it never rewrites *string* constants. Reflecting by name
     * (`getMethod("hook")`, `getMethod("intercept")`, ...) therefore throws
     * NoSuchMethodException on protected frameworks, and because every caller
     * used `runCatching`, the hooks silently never installed while the UI still
     * reported "已激活". Typed calls work with and without protection.
     */
    fun useModern(module: Any, apiVersion: Int) {
        require(apiVersion >= XposedInterface.API_102) {
            "Modern runtime requires libxposed API 102+, got $apiVersion"
        }
        val api = requireNotNull(module as? XposedInterface) {
            "HookRuntime module ${module.javaClass.name} is not an XposedInterface"
        }
        backend = ModernHookBackend(api)
        logFrameworkInfo(api, apiVersion)
        remotePreferences = runCatching { api.getRemotePreferences("settings") }
            .onFailure { Log.e("HookRuntime getRemotePreferences failed"); Log.e(it) }
            .getOrNull()
        Log.i("HookRuntime remote preferences=" + (remotePreferences != null))
    }

    /** Framework name/version, shown in the runtime log for bug reports. */
    private fun logFrameworkInfo(api: XposedInterface, apiVersion: Int) {
        fun read(block: () -> Any?): Any? = runCatching(block).getOrNull()
        val props = read { api.frameworkProperties }
        Log.i(
            "HookRuntime framework=${read { api.frameworkName }} version=${read { api.frameworkVersion }}" +
                    " code=${read { api.frameworkVersionCode }} api=$apiVersion props=$props" +
                    " apiProtection=${props is Long && (props and XposedInterface.PROP_RT_API_PROTECTION) != 0L}"
        )
    }

    internal fun backend(): HookBackend = checkNotNull(backend) { "Hook runtime is not initialized" }

    fun remotePreferences(): SharedPreferences? = remotePreferences

    /** Any named remote-preference group, e.g. the runtime-log side channel. */
    fun remotePreferences(group: String): SharedPreferences? = runCatching {
        (backend as? ModernHookBackend)?.api()?.getRemotePreferences(group)
    }.onFailure(Log::e).getOrNull()

    /** Names of the files the module app published for the hooked process. */
    fun listModuleFiles(): Array<String> = runCatching {
        (backend as? ModernHookBackend)?.api()?.listRemoteFiles()
    }.onFailure { Log.w("HookRuntime listRemoteFiles failed: ${it.message}") }.getOrNull()
        ?: emptyArray()

    /**
     * Reads a file the module app published in ITS OWN private `files/` dir.
     *
     * libxposed opens remote files read-only for injected modules, which is
     * exactly what the hooked process needs: a framework-native, no-permission
     * channel for the settings snapshot. LSPosed 2.1/2.2 and Vector both create
     * `<lspd>/config/<user>/<module>/files/` with the module's SELinux label on
     * the first call, so this also works before the module app ever ran.
     */
    fun readModuleFile(name: String, limit: Int = 512 * 1024): String? = runCatching {
        val pfd = openModuleFile(name) ?: return null
        try {
            // Read through the descriptor directly: wrapping it in a stream and
            // closing that stream would close the fd twice.
            val buffer = java.io.ByteArrayOutputStream()
            val chunk = ByteArray(16 * 1024)
            var total = 0
            while (total < limit) {
                val read = android.system.Os.read(pfd.fileDescriptor, chunk, 0, chunk.size)
                if (read <= 0) break
                buffer.write(chunk, 0, read)
                total += read
            }
            if (total <= 0) null else buffer.toString("UTF-8")
        } finally {
            runCatching { pfd.close() }
        }
    }.onFailure { Log.w("HookRuntime readModuleFile($name) failed: ${it.message}") }.getOrNull()

    /**
     * Opens a file inside the MODULE app's private `files/` dir from the hooked
     * process. The framework creates/owns the descriptor, so neither the host
     * nor the module needs extra storage permissions and package visibility is
     * irrelevant. Only single file names are accepted by the framework.
     */
    fun openModuleFile(name: String): ParcelFileDescriptor? = runCatching {
        (backend as? ModernHookBackend)?.api()?.openRemoteFile(name)
    }.onFailure {
        Log.w("HookRuntime openRemoteFile($name) failed: ${it.message}")
    }.getOrNull()
}

private object LegacyHookBackend : HookBackend {
    override fun hook(member: Member, kind: HookKind, callback: (HookParam) -> Any?) = try {
        when (kind) {
            HookKind.BEFORE -> XposedBridge.hookMethod(member, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val outcome = HookOutcome()
                    val bridge = HookParam(
                        member = member,
                        thisObject = param.thisObject,
                        args = param.args as Array<Any?>,
                        outcome = outcome,
                        originalInvoker = { args ->
                            XposedBridge.invokeOriginalMethod(member, param.thisObject, args)
                        }
                    )
                    callback(bridge)
                    if (outcome.hasOutcome) {
                        outcome.throwable?.let {
                            param.throwable = it
                        } ?: run {
                            param.result = outcome.result
                        }
                    }
                }
            })
            HookKind.AFTER -> XposedBridge.hookMethod(member, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val outcome = HookOutcome(
                        hasOutcome = true,
                        initialResult = param.result,
                        initialThrowable = param.throwable
                    )
                    val bridge = HookParam(
                        member = member,
                        thisObject = param.thisObject,
                        args = param.args as Array<Any?>,
                        outcome = outcome,
                        originalInvoker = { args ->
                            XposedBridge.invokeOriginalMethod(member, param.thisObject, args)
                        }
                    )
                    callback(bridge)
                    outcome.throwable?.let {
                        param.throwable = it
                    } ?: run {
                        param.result = outcome.result
                    }
                }
            })
            HookKind.REPLACE -> XposedBridge.hookMethod(member, object : XC_MethodReplacement() {
                override fun replaceHookedMethod(param: MethodHookParam): Any? {
                    val outcome = HookOutcome()
                    val bridge = HookParam(
                        member = member,
                        thisObject = param.thisObject,
                        args = param.args as Array<Any?>,
                        outcome = outcome,
                        originalInvoker = { args ->
                            XposedBridge.invokeOriginalMethod(member, param.thisObject, args)
                        }
                    )
                    val replaced = callback(bridge)
                    outcome.throwable?.let { throw it }
                    return if (outcome.hasOutcome) outcome.result else replaced
                }
            })
        }
    } catch (e: Throwable) {
        Log.e(e)
        null
    }

    override fun hookAllMethods(
        clazz: Class<*>,
        methodName: String?,
        kind: HookKind,
        callback: (HookParam) -> Any?
    ): Set<Any> = runCatching {
        XposedBridge.hookAllMethods(clazz, methodName, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                if (kind != HookKind.BEFORE) return
                val outcome = HookOutcome()
                val bridge = HookParam(
                    member = param.method,
                    thisObject = param.thisObject,
                    args = param.args as Array<Any?>,
                    outcome = outcome
                ) { args ->
                    XposedBridge.invokeOriginalMethod(param.method, param.thisObject, args)
                }
                callback(bridge)
                if (outcome.hasOutcome) {
                    outcome.throwable?.let {
                        param.throwable = it
                    } ?: run {
                        param.result = outcome.result
                    }
                }
            }

            override fun afterHookedMethod(param: MethodHookParam) {
                if (kind != HookKind.AFTER) return
                val outcome = HookOutcome(
                    hasOutcome = true,
                    initialResult = param.result,
                    initialThrowable = param.throwable
                )
                val bridge = HookParam(
                    member = param.method,
                    thisObject = param.thisObject,
                    args = param.args as Array<Any?>,
                    outcome = outcome
                ) { args ->
                    XposedBridge.invokeOriginalMethod(param.method, param.thisObject, args)
                }
                callback(bridge)
                outcome.throwable?.let {
                    param.throwable = it
                } ?: run {
                    param.result = outcome.result
                }
            }
        }).mapTo(linkedSetOf()) { it as Any }
    }.getOrElse {
        Log.e(it)
        emptySet()
    }

    override fun hookAllConstructors(
        clazz: Class<*>,
        kind: HookKind,
        callback: (HookParam) -> Any?
    ): Set<Any> = runCatching {
        XposedBridge.hookAllConstructors(clazz, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                if (kind != HookKind.BEFORE) return
                val outcome = HookOutcome()
                val bridge = HookParam(
                    member = param.method,
                    thisObject = param.thisObject,
                    args = param.args as Array<Any?>,
                    outcome = outcome
                ) { args ->
                    XposedBridge.invokeOriginalMethod(param.method, param.thisObject, args)
                }
                callback(bridge)
                if (outcome.hasOutcome) {
                    outcome.throwable?.let {
                        param.throwable = it
                    } ?: run {
                        param.result = outcome.result
                    }
                }
            }

            override fun afterHookedMethod(param: MethodHookParam) {
                if (kind != HookKind.AFTER) return
                val outcome = HookOutcome(
                    hasOutcome = true,
                    initialResult = param.result,
                    initialThrowable = param.throwable
                )
                val bridge = HookParam(
                    member = param.method,
                    thisObject = param.thisObject,
                    args = param.args as Array<Any?>,
                    outcome = outcome
                ) { args ->
                    XposedBridge.invokeOriginalMethod(param.method, param.thisObject, args)
                }
                callback(bridge)
                outcome.throwable?.let {
                    param.throwable = it
                } ?: run {
                    param.result = outcome.result
                }
            }
        }).mapTo(linkedSetOf()) { it as Any }
    }.getOrElse {
        Log.e(it)
        emptySet()
    }
}

private class ModernHookBackend(
    private val api: XposedInterface
) : HookBackend {
    fun api(): XposedInterface = api

    override fun hook(member: Member, kind: HookKind, callback: (HookParam) -> Any?): Any? =
        runCatching { hookTyped(member, kind, callback) }.onFailure(Log::e).getOrNull()

    override fun hookAllMethods(
        clazz: Class<*>,
        methodName: String?,
        kind: HookKind,
        callback: (HookParam) -> Any?
    ): Set<Any> = clazz.declaredMethods
        .filter { methodName == null || it.name == methodName }
        .mapNotNullTo(linkedSetOf()) { hook(it, kind, callback) }

    override fun hookAllConstructors(
        clazz: Class<*>,
        kind: HookKind,
        callback: (HookParam) -> Any?
    ): Set<Any> = clazz.declaredConstructors
        .mapNotNullTo(linkedSetOf()) { hook(it, kind, callback) }

    /**
     * Uses the typed `XposedInterface` members exclusively. No reflective lookup
     * by string name, so LSPosed's runtime API protection cannot break it.
     */
    private fun hookTyped(member: Member, kind: HookKind, callback: (HookParam) -> Any?): Any? {
        // The hooker must never let its own exceptions escape: libxposed turns
        // them into crashes of the hooked app in PROTECTIVE mode and silently
        // disables the hook otherwise. Log and fall through to the original.
        val hooker = object : XposedInterface.Hooker {
            override fun intercept(chain: XposedInterface.Chain): Any? = try {
                interceptTyped(member, kind, callback, chain)
            } catch (t: Throwable) {
                Log.e("Hook ${describe(member)} threw: ${t.javaClass.simpleName}: ${t.message}")
                Log.e(t)
                chain.proceed()
            }
        }
        return api.hook(member as Executable).intercept(hooker)
    }

    private fun describe(member: Member): String {
        val owner = member.declaringClass?.name ?: "?"
        val name = member.name
        val params = (member as? Executable)?.parameterTypes?.joinToString(",") { it.simpleName } ?: ""
        return "$owner#$name($params)"
    }

    private fun interceptTyped(
        member: Member,
        kind: HookKind,
        callback: (HookParam) -> Any?,
        chain: XposedInterface.Chain
    ): Any? {
        val thisObject = try {
            chain.thisObject
        } catch (ignored: Throwable) {
            // Static methods reject thisObject access; keep the hook alive.
            null
        }
        val currentArgs = try {
            chain.args.toTypedArray()
        } catch (ignored: Throwable) {
            emptyArray()
        }
        val origin = { args: Array<Any?> -> invokeOrigin(member, thisObject, args) }
        return when (kind) {
            HookKind.BEFORE -> {
                val outcome = HookOutcome()
                val bridge = HookParam(member, thisObject, currentArgs.copyOf(), outcome, origin)
                callback(bridge)
                outcome.throwable?.let { throw it }
                if (outcome.hasOutcome) outcome.result else chain.proceed(bridge.args)
            }
            HookKind.AFTER -> {
                var result: Any? = null
                var error: Throwable? = null
                try {
                    result = chain.proceed(currentArgs)
                } catch (t: Throwable) {
                    error = t
                }
                val outcome = HookOutcome(
                    hasOutcome = true,
                    initialResult = result,
                    initialThrowable = error
                )
                val bridge = HookParam(member, thisObject, currentArgs.copyOf(), outcome, origin)
                callback(bridge)
                outcome.throwable?.let { throw it }
                outcome.result
            }
            HookKind.REPLACE -> {
                val outcome = HookOutcome()
                val bridge = HookParam(member, thisObject, currentArgs.copyOf(), outcome, origin)
                val replaced = callback(bridge)
                outcome.throwable?.let { throw it }
                if (outcome.hasOutcome) outcome.result else replaced
            }
        }
    }

    private fun invokeOrigin(member: Member, thisObject: Any?, args: Array<Any?>): Any? {
        return when (member) {
            is Method -> api.getInvoker(member)
                .setType(XposedInterface.Invoker.Type.ORIGIN)
                .invoke(thisObject, *args)
            is Constructor<*> -> api.getInvoker(member)
                .setType(XposedInterface.Invoker.Type.ORIGIN)
                .newInstance(*args)
            else -> null
        }
    }
}
