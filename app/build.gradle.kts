plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.rasel.foldbook"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.rasel.foldbook"
        minSdk = 26
        targetSdk = 34
        versionCode = 3
        versionName = "1.2"
    }

    // Fixed signing key so every new APK installs as an update (login and data stay).
    // The keystore is password-protected; the password comes from the GitHub secret
    // FOLDBOOK_KEY_PASSWORD and is never stored in the repo.
    val keyPassword: String? = System.getenv("FOLDBOOK_KEY_PASSWORD")
    signingConfigs {
        if (keyPassword != null) {
            create("foldbook") {
                storeFile = file("foldbook.jks")
                storePassword = keyPassword
                keyAlias = "foldbook"
                this.keyPassword = keyPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("foldbook")
                ?: error("FOLDBOOK_KEY_PASSWORD secret is missing - add it in GitHub repo settings")
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

// No extra libraries needed — uses only the Android framework.
