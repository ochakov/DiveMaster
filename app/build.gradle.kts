plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.ochakov.divemaster"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.ochakov.divemaster"
        minSdk = 30
        targetSdk = 34
        versionCode = 3
        versionName = "0.9.0"
    }

    buildFeatures {
        compose = true
    }

    signingConfigs {
        create("release") {
            // Provided by CI (or a local shell) via environment; when absent,
            // release builds fall back to debug signing so they stay installable.
            val keystorePath = System.getenv("DIVEMASTER_KEYSTORE_FILE")?.takeIf { it.isNotBlank() }
            if (keystorePath != null) {
                storeFile = file(keystorePath)
                storePassword = System.getenv("DIVEMASTER_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("DIVEMASTER_KEY_ALIAS")
                // PKCS12 keystores share one password; the key-specific secret is optional.
                keyPassword = System.getenv("DIVEMASTER_KEY_PASSWORD")?.takeIf { it.isNotBlank() }
                    ?: System.getenv("DIVEMASTER_KEYSTORE_PASSWORD")
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
    implementation(project(":core:engine"))

    implementation(libs.androidx.core.ktx)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.wear.compose.material)
    implementation(libs.wear.compose.foundation)
    implementation(libs.wear.compose.navigation)

    implementation(libs.activity.compose)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.lifecycle.viewmodel.compose)

    implementation(libs.play.services.wearable)
    implementation(libs.coroutines.play.services)
}
