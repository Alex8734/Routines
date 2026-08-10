plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

android {
    namespace = "at.resch.routines"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "at.resch.routines"
        minSdk = 29
        targetSdk = 36
        // Überschreibbar durch die Release-CI: -PversionCode=42 -PversionName=1.2.3
        // (siehe .github/workflows/release.yml). Ohne Property gelten die Defaults
        // für lokale Builds.
        versionCode = (project.findProperty("versionCode") as String?)?.toIntOrNull() ?: 1
        versionName = (project.findProperty("versionName") as String?) ?: "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // Release-Signierung über Umgebungsvariablen — die CI legt den Keystore aus
    // einem Secret ab und setzt KEYSTORE_FILE/-PASSWORD, KEY_ALIAS/-PASSWORD.
    // Fehlt der Keystore (lokaler Build, Fork ohne Secrets), wird die Config gar
    // nicht erst angelegt und `assembleRelease` erzeugt ein unsigniertes APK.
    // `providers.environmentVariable` statt System.getenv: Configuration-Cache-tauglich.
    // takeIf(isNotBlank) ist wichtig: die CI setzt die Variable auch dann, wenn der
    // Keystore-Step übersprungen wurde — dann aber leer, und file("") wäre das
    // Projektverzeichnis. isFile schließt zusätzlich Verzeichnisse aus.
    val keystore = providers.environmentVariable("KEYSTORE_FILE").orNull
        ?.takeIf { it.isNotBlank() }
        ?.let { rootProject.file(it) }
        ?.takeIf { it.isFile }

    signingConfigs {
        if (keystore != null) {
            create("release") {
                storeFile = keystore
                storePassword = providers.environmentVariable("KEYSTORE_PASSWORD").orNull
                keyAlias = providers.environmentVariable("KEY_ALIAS").orNull
                keyPassword = providers.environmentVariable("KEY_PASSWORD").orNull
            }
        }
    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
            signingConfig = signingConfigs.findByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
    }
    testOptions {
        // android.util.Log calls in production code (MacroEngine, MacroEvaluator, etc.)
        // return default values (0/false/null) instead of throwing "not mocked" under
        // JVM unit tests. This is lighter than Robolectric and keeps the test suite fast.
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.core)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.reorderable)
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.mockk)
    testImplementation(libs.turbine)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.mockk.android)
    androidTestImplementation(libs.turbine)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}