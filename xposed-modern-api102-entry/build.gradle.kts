plugins {
    id("com.android.library")
}

android {
    namespace = "com.akari.ppx.xp.modern.api102"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        buildConfig = false
    }
}

dependencies {
    compileOnly(Libs.libxposed_api)
}
