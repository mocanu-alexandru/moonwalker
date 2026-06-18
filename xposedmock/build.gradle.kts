plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.alexmcn.mwxposed"
    compileSdk = 34
    defaultConfig {
        applicationId = "com.alexmcn.mwxposed"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }
    // Semnătură consistentă (același keystore ca app-ul) ca să se poată instala/actualiza ușor.
    val ksFile = rootProject.file("keystore/moonwalker-debug.p12")
    if (ksFile.exists()) {
        signingConfigs {
            create("consistent") {
                storeFile = ksFile
                storePassword = "moonwalker"; keyAlias = "moonwalker"; keyPassword = "moonwalker"
            }
        }
    }
    buildTypes {
        debug   { signingConfigs.findByName("consistent")?.let { signingConfig = it } }
        release { isMinifyEnabled = false; signingConfigs.findByName("consistent")?.let { signingConfig = it } }
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    // API Xposed — furnizat la runtime de LSPosed, doar compileOnly.
    compileOnly("de.robv.android.xposed:api:82")
}
