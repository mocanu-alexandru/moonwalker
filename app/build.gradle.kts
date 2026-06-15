plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val gitCommitCount = try {
    Runtime.getRuntime().exec("git rev-list --count HEAD")
        .inputStream.bufferedReader().readText().trim().toInt()
} catch (_: Exception) { 1 }

android {
    namespace = "com.alexmcn.moonwalker"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.alexmcn.moonwalker"
        minSdk = 26
        targetSdk = 34
        versionCode = gitCommitCount
        versionName = "1.0.$gitCommitCount"
    }

    // Keystore consistent pentru toate build-urile (debug distribuit via repo, nu producție)
    val ksFile = rootProject.file("keystore/moonwalker-debug.p12")
    if (ksFile.exists()) {
        signingConfigs {
            create("consistent") {
                storeFile = ksFile
                storePassword = "moonwalker"
                keyAlias = "moonwalker"
                keyPassword = "moonwalker"
            }
        }
    }

    buildTypes {
        debug {
            val sc = signingConfigs.findByName("consistent")
            if (sc != null) signingConfig = sc
        }
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            val sc = signingConfigs.findByName("consistent")
            if (sc != null) signingConfig = sc
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("org.osmdroid:osmdroid-android:6.1.18")
    implementation("com.google.android.gms:play-services-location:21.3.0")
}
