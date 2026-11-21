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
        versionCode = 3
        versionName = "1.2"

        // Where Pro is sold, and the Google OAuth *web* client ID the website uses
        // (NEXT_PUBLIC_GOOGLE_CLIENT_ID). Set as GitHub repository variables
        // NOSCROLL_SITE_URL / NOSCROLL_GOOGLE_WEB_CLIENT_ID, or in gradle.properties.
        fun setting(env: String, prop: String): String =
            System.getenv(env)?.takeIf { it.isNotBlank() }
                ?: (project.findProperty(prop) as String?) ?: ""
        buildConfigField("String", "SITE_URL", "\"${setting("NOSCROLL_SITE_URL", "noscroll.siteUrl")}\"")
        buildConfigField(
            "String", "GOOGLE_WEB_CLIENT_ID",
            "\"${setting("NOSCROLL_GOOGLE_WEB_CLIENT_ID", "noscroll.googleWebClientId")}\"",
        )
    }

    buildFeatures {
        buildConfig = true
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
        // Store version: free + 7-day trial + Pro bought with Stripe on the website,
        // Google sign-in. No ad blocker (Google Play policy).
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
    // Sign in with Google (Credential Manager).
    "playImplementation"("androidx.credentials:credentials:1.3.0")
    "playImplementation"("androidx.credentials:credentials-play-services-auth:1.3.0")
    "playImplementation"("com.google.android.libraries.identity.googleid:googleid:1.1.1")
}
