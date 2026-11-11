package com.akari.ppx.utils

import org.junit.Assert.assertNotNull
import org.junit.Test
import java.net.URLClassLoader

class ModernRuntimeIsolationTest {
    @Test
    fun runtimeInitializationDoesNotLoadLegacyBackend() {
        val runtimeName = "com.akari.ppx.utils.HookRuntime"
        val legacyBackendName = "com.akari.ppx.utils.LegacyHookBackend"
        val source = requireNotNull(HookRuntime::class.java.protectionDomain?.codeSource?.location)
        val loader = object : URLClassLoader(arrayOf(source), javaClass.classLoader) {
            override fun loadClass(name: String, resolve: Boolean): Class<*> {
                check(name != legacyBackendName && !name.startsWith("de.robv.android.xposed.")) {
                    "Modern runtime initialized legacy class: $name"
                }
                if (name != runtimeName) return super.loadClass(name, resolve)
                return synchronized(this) {
                    (findLoadedClass(name) ?: findClass(name)).also {
                        if (resolve) resolveClass(it)
                    }
                }
            }
        }
        loader.use {
            val runtime = Class.forName(runtimeName, true, it)
            assertNotNull(runtime.getField("INSTANCE").get(null))
        }
    }
}
