plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.noscroll.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.noscroll.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 2
        versionName = "1.1"
    }

    // Upload key for Google Play, supplied by CI secrets (see README).
    // Without it, builds are signed with the debug key (fine for sideloading only).
    val uploadKeystore = System.getenv("UPLOAD_KEYSTORE_FILE")
    signingConfigs {
        if (uploadKeystore != null && file(uploadKeystore).exists()) {
            create("upload") {
                storeFile = file(uploadKeystore)
                storePassword = System.getenv("UPLOAD_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("UPLOAD_KEY_ALIAS")
                keyPassword = System.getenv("UPLOAD_KEY_PASSWORD")
            }
        }
    }

    flavorDimensions += "store"
    productFlavors {
        // Google Play: free + 7-day trial + Pro via Play billing. No ad blocker (Play policy).
        create("play") {
            dimension = "store"
        }
        // Personal sideloaded build: everything unlocked, includes the ad blocker.
        create("full") {
            dimension = "store"
            applicationIdSuffix = ".full"
            versionNameSuffix = "-full"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("upload") ?: signingConfigs.getByName("debug")
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
    "playImplementation"("com.android.billingclient:billing:7.1.1")
}
