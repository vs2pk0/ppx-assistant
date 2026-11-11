package com.akari.ppx.xp.modern;

import android.util.Log;

import java.lang.reflect.Method;

import io.github.libxposed.api.XposedModule;

public class Api102Entry extends XposedModule {
    private static final String TAG = "PPXPPX";

    @Override
    public void onModuleLoaded(ModuleLoadedParam param) {
        invokeBridge("onModernModuleLoaded", this, getApiVersion(), param.getProcessName(), param.isSystemServer());
    }

    @Override
    public void onPackageReady(PackageReadyParam param) {
        invokeBridge("onModernPackageLoaded", this, getApiVersion(), param.getPackageName(), param.getClassLoader());
    }

    private static void invokeBridge(String methodName, Object... args) {
        try {
            Class<?> bridge = Class.forName("com.akari.ppx.xp.ModuleEntryBridge");
            Method method = null;
            for (Method candidate : bridge.getDeclaredMethods()) {
                if (candidate.getName().equals(methodName) && candidate.getParameterTypes().length == args.length) {
                    method = candidate;
                    break;
                }
            }
            if (method == null) {
                throw new NoSuchMethodException(methodName);
            }
            method.invoke(null, args);
        } catch (Throwable t) {
            Log.e(TAG, "Api102Entry bridge failed", t);
        }
    }
}
