import org.gradle.api.tasks.Copy
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import java.util.*

plugins {
    id("com.android.application")
    kotlin("android")
}

val properties = Properties()
project.rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use {
    properties.load(it)
}

val verCode = 20260923
val verName = "0.0.6"

android {
    compileSdk = 36

    namespace = "com.akari.ppx"

    defaultConfig {
        applicationId = "io.github.vs2pk0.piyou"
        minSdk = 26

        targetSdk = 36

        versionCode = verCode
        versionName = verName
        testInstrumentationRunner = "com.akari.ppx.test.PreferenceInstrumentation"

        vectorDrawables {
            useSupportLibrary = true
        }
    }

    val config = (System.getenv("PPX_KEYSTORE_FILE") ?: properties.getProperty("storeFile"))?.let {
        signingConfigs.create("config") {
            storeFile = file(it)
            storePassword = System.getenv("PPX_STORE_PASSWORD") ?: properties.getProperty("storePassword")
            keyAlias = System.getenv("PPX_KEY_ALIAS") ?: properties.getProperty("keyAlias")
            keyPassword = System.getenv("PPX_KEY_PASSWORD") ?: properties.getProperty("keyPassword")
        }
    }

    buildTypes {
        all {
            signingConfig = config ?: signingConfigs["debug"]
        }

        getByName("release") {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility(JavaVersion.VERSION_17)
        targetCompatibility(JavaVersion.VERSION_17)
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        buildConfig = true
        compose = true
    }
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }
    packaging {
        resources {
            merges += "META-INF/xposed/*"
            excludes += arrayOf(
                "META-INF/AL2.0",
                "META-INF/LGPL2.1",
                "META-INF/LICENSE*",
                "META-INF/NOTICE*",
                "META-INF/DEPENDENCIES",
                "META-INF/*.version",
                "kotlin/**",
                "google/**",
                "**.bin"
            )
        }
    }
}

dependencies {
    implementation("com.squareup:gifencoder:0.10.1")
    implementation("io.noties.markwon:core:4.6.2")
    compileOnly(Libs.xposed_api)
    implementation(project(":xposed-modern-api101-entry"))
    implementation("io.github.libxposed:service:101.0.0") {
        exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib")
    }
    api(Libs.gson)
    implementation(AndroidX.dataStore.preferences)
    implementation(AndroidX.compose.material)
    implementation(AndroidX.compose.ui.tooling)
    implementation(AndroidX.appCompat)
    implementation(AndroidX.activity.compose)
    implementation(Google.android.material)
    implementation(Libs.reorderable)
    implementation(Libs.mp4parser)
    testImplementation("junit:junit:4.13.2")
}

tasks.withType<KotlinCompile>().configureEach {
    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs = freeCompilerArgs + listOf(
            "-opt-in=androidx.compose.material.ExperimentalMaterialApi",
            "-opt-in=androidx.compose.animation.ExperimentalAnimationApi",
            "-opt-in=androidx.compose.foundation.ExperimentalFoundationApi",
            "-opt-in=androidx.compose.ui.ExperimentalComposeUiApi"
        )

        if (name.contains("release", true)) {
            freeCompilerArgs = freeCompilerArgs + listOf(
                "-Xassertions=always-disable",
                "-Xno-param-assertions",
                "-Xno-call-assertions",
                "-Xno-receiver-assertions",
                "-opt-in=kotlin.RequiresOptIn"
            )
        }
    }
}

val renameReleaseApk by tasks.registering(Copy::class) {
    dependsOn("assembleRelease")
    from(layout.buildDirectory.file("outputs/apk/release/app-release.apk"))
    into(layout.buildDirectory.dir("outputs/release-dist"))
    rename { "皮友助手-v${verName}-release.apk" }
}

tasks.matching { it.name == "assembleRelease" }.configureEach {
    finalizedBy(renameReleaseApk)
}
