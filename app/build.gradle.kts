plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "dev.yama"
    compileSdk = 35
    defaultConfig {
        applicationId = "dev.yama"
        minSdk = 28
        targetSdk = 35
        versionCode = 2
        versionName = "0.2"
        ndk { abiFilters += "armeabi-v7a" }
    }
    // release key lives outside the repo (YAMA_KEYSTORE* in ~/.gradle/gradle.properties); without it, fall back to the debug key
    val keystore = providers.gradleProperty("YAMA_KEYSTORE").orNull
    signingConfigs {
        if (keystore != null) create("release") {
            storeFile = file(keystore)
            storePassword = providers.gradleProperty("YAMA_KEYSTORE_PASSWORD").get()
            keyAlias = "yama"
            keyPassword = storePassword
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            signingConfig = signingConfigs.getByName(if (keystore != null) "release" else "debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    lint { disable += "UnsafeOptInUsageError" } // media3 @UnstableApi APIs are used on purpose
}

dependencies {
    implementation("androidx.media3:media3-exoplayer:1.5.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.5.1") // radio streams are HLS
    implementation("androidx.media3:media3-session:1.5.1")
    implementation("com.google.zxing:core:3.5.3")
}
