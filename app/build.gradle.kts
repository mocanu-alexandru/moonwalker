plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val gitCommitCount = try {
    Runtime.getRuntime().exec("git rev-list --count HEAD")
        .inputStream.bufferedReader().readText().trim().toInt()
} catch (_: Exception) { 1 }

// Offset ca versiunea să continue după squash-ul istoricului (ultima versiune pre-squash: 1.0.58).
// După squash gitCommitCount reîncepe de la 1, deci 1 + 58 = 59 → următorul build e 1.0.59.
val versionBase = 58
val versionSerial = gitCommitCount + versionBase

android {
    namespace = "com.alexmcn.moonwalker"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.alexmcn.moonwalker"
        minSdk = 26
        targetSdk = 34
        versionCode = versionSerial
        versionName = "1.0.$versionSerial"

        // Token read-only pt. auto-update dintr-un repo privat (injectat din CI via env
        // MW_UPDATE_TOKEN; gol la build local → auto-update inactiv, restul appului merge).
        buildConfigField("String", "UPDATE_TOKEN", "\"${System.getenv("MW_UPDATE_TOKEN") ?: ""}\"")
    }

    buildFeatures {
        buildConfig = true
    }

    // libh3-java.so din AAR nu declară libm în NEEDED (→ "cannot locate symbol cos"). Folosim
    // o copie patched (patchelf --add-needed libm.so) din src/main/jniLibs; pickFirst o preferă
    // pe a noastră în fața celei din AAR.
    packaging {
        jniLibs {
            pickFirsts += "**/libh3-java.so"
        }
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
    // H3 nativ pt. Android (AAR cu .so arm64-v8a/armeabi-v7a) — test apartenență celule res-10
    implementation("com.uber:h3-android:4.3.2")
}
