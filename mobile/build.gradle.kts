plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.ochakov.divemaster.mobile"
    compileSdk = 35

    defaultConfig {
        // Same applicationId as the wear app: required for one Play listing
        // and for the Wearable Data Layer to connect the two.
        applicationId = "com.ochakov.divemaster"
        minSdk = 26
        targetSdk = 34
        versionCode = 2
        versionName = "0.8.0"
    }

    buildFeatures {
        compose = true
    }

    signingConfigs {
        create("release") {
            // Must match the wear app's signing (same env vars) — the Wearable
            // Data Layer only connects apps with identical signatures.
            val keystorePath = System.getenv("DIVEMASTER_KEYSTORE_FILE")?.takeIf { it.isNotBlank() }
            if (keystorePath != null) {
                storeFile = file(keystorePath)
                storePassword = System.getenv("DIVEMASTER_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("DIVEMASTER_KEY_ALIAS")
                keyPassword = System.getenv("DIVEMASTER_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            val hasReleaseKeystore = System.getenv("DIVEMASTER_KEYSTORE_FILE")?.isNotBlank() == true
            signingConfig = if (hasReleaseKeystore) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
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
    implementation(project(":core:deco"))
    implementation(project(":core:data"))

    implementation(libs.androidx.core.ktx)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.runtime.compose)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.play.services.wearable)
    implementation(libs.coroutines.play.services)
}
