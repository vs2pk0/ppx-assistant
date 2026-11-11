-repackageclasses "ppx"

-keep class com.akari.ppx.xp.Entry {
    <init>();
}

-keep class com.akari.ppx.xp.ModuleEntryBridge {
    *;
}

-keep class com.akari.ppx.xp.modern.Api102Entry {
    <init>(...);
    *;
}

# API 102 modern modules cannot load legacy Xposed APIs. Keep legacy code
# isolated: R8 must not inline it into classes used by the modern entry.
-keep class com.akari.ppx.utils.LegacyHookBackend { *; }
-keep class com.akari.ppx.data.LegacyPreferences { *; }

-keep class com.akari.ppx.data.model.**{*;}
-keep interface com.akari.ppx.xp.hook.BaseHook
-keep class * implements com.akari.ppx.xp.hook.BaseHook {*;}

-keep class com.akari.ppx.ui.MainActivity$Companion {
    boolean isModuleActive(...);
}
-keep class com.akari.ppx.data.alive.AliveActivity$Companion

-keep class * implements com.coremedia.iso.boxes.Box {*;}

-keepattributes EnclosingMethod
-keepattributes InnerClasses
-keepattributes Signature

-dontpreverify
-overloadaggressively

-dontwarn io.github.libxposed.api.**
-dontwarn io.github.libxposed.annotation.**

# Gson reads/writes custom emoticon groups; R8 must not strip @SerializedName
# nor rename these fields, otherwise the JSON on disk becomes unreadable
# after every update (it used to degrade to a/b/c/d).
-keepattributes *Annotation*
-keep class com.akari.ppx.utils.CustomEmoticon { *; }
-keep class com.akari.ppx.utils.EmoticonGroup { *; }
