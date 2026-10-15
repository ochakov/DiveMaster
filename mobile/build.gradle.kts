import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// Release identity shared with :app (gradle.properties); see there for the
// watch's +10000 offset.
val baseVersionCode = providers.gradleProperty("divemaster.versionCode").get().toInt()
val releaseVersionName = providers.gradleProperty("divemaster.versionName").get()

// Guard against the mistake of 2026-10-15: without the keystore environment a
// "release" build is silently debug-signed, and Google Play rejects it. Fail
// release packaging unless a local check build explicitly asks for it.
val hasReleaseKeystore = System.getenv("DIVEMASTER_KEYSTORE_FILE")?.isNotBlank() == true
val allowDebugSignedRelease = providers.gradleProperty("divemaster.allowDebugRelease").orNull == "true"
gradle.taskGraph.whenReady {
    val packagesRelease = allTasks.any {
        it.project == project && it.name.matches(Regex("(assemble|bundle|package)Release"))
    }
    if (packagesRelease && !hasReleaseKeystore && !allowDebugSignedRelease) {
        throw GradleException(
            "${project.path}: release packaging without DIVEMASTER_KEYSTORE_* in the environment would " +
                "produce a DEBUG-signed artifact that Google Play rejects. Use the release workflow (push a " +
                "v* tag), set the keystore env, or pass -Pdivemaster.allowDebugRelease=true for a local check.",
        )
    }
}

android {
    namespace = "com.ochakov.divemaster.mobile"
    compileSdk = 36

    defaultConfig {
        // Same applicationId as the wear app: required for one Play listing
        // and for the Wearable Data Layer to connect the two.
        applicationId = "com.ochakov.divemaster"
        minSdk = 26
        // Google Play's phone floor (since 2026-08-31) is Android 16.
        targetSdk = 36
        versionCode = baseVersionCode
        versionName = releaseVersionName
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
                // PKCS12 keystores share one password; the key-specific secret is optional.
                keyPassword = System.getenv("DIVEMASTER_KEY_PASSWORD")?.takeIf { it.isNotBlank() }
                    ?: System.getenv("DIVEMASTER_KEYSTORE_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
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
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
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
