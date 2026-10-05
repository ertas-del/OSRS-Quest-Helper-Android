plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.questoverlay"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.questoverlay"
        minSdk = 30
        targetSdk = 36
        // Every cloud build gets a higher number, so a new APK always installs over the old one.
        val run = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1
        versionCode = run
        versionName = "0.2.$run"

        // Only modern 64-bit ARM phones (like the S26). The text reader's engine is ~11 MB per
        // chip type, so bundling all four made the APK 45 MB.
        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    // A fixed debug key (committed on purpose) so every build is signed the same way
    // and a new APK installs over the old one without losing your quest progress.
    // The release key is private: the cloud build decrypts signing/release.jks.enc with the
    // RELEASE_PASSWORD secret and points RELEASE_STORE_FILE at it. Without it, release builds
    // aren't signed and the workflow builds the debug APK instead.
    val releaseStore = System.getenv("RELEASE_STORE_FILE")?.let { file(it) }?.takeIf { it.exists() }
    val releasePassword = System.getenv("RELEASE_PASSWORD").orEmpty()

    signingConfigs {
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
        if (releaseStore != null && releasePassword.isNotEmpty()) {
            create("release") {
                storeFile = releaseStore
                storeType = "pkcs12"
                storePassword = releasePassword
                keyAlias = System.getenv("RELEASE_KEY_ALIAS") ?: "questoverlay"
                keyPassword = releasePassword
            }
        }
    }

    buildTypes {
        getByName("release") {
            // Shrink and obfuscate release builds (see proguard-rules.pro).
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release")
        }
    }

    lint {
        // Lint findings are reported, not allowed to block a release build.
        checkReleaseBuilds = false
        abortOnError = false
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    // On-phone text recognition for Auto-check. The Latin model is bundled in the app, so it works
    // offline and nothing is sent anywhere.
    implementation("com.google.mlkit:text-recognition:16.0.1")
}
