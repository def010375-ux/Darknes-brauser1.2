plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

val releaseKeystorePath = System.getenv("DARKNES_KEYSTORE_PATH")

android {
    namespace = "com.darknes.tor"
    compileSdk = 37
    compileSdkMinor = 2

    defaultConfig {
        applicationId = "com.darknes.tor"
        minSdk = 26
        targetSdk = 36
        versionCode = 6
        versionName = "1.5-beta"
    }

    signingConfigs {
        create("release") {
            if (!releaseKeystorePath.isNullOrBlank()) {
                storeFile = file(releaseKeystorePath)
                storePassword = System.getenv("DARKNES_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("DARKNES_KEY_ALIAS")
                keyPassword = System.getenv("DARKNES_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        getByName("release") {
            if (!releaseKeystorePath.isNullOrBlank()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.19.1")
    implementation("androidx.activity:activity-compose:1.13.0")

    implementation("androidx.compose.ui:ui:1.12.1")
    implementation("androidx.compose.ui:ui-tooling-preview:1.12.1")
    implementation("androidx.compose.foundation:foundation:1.12.1")
    implementation("androidx.compose.material3:material3:1.4.0")
    implementation("androidx.compose.material:material-icons-extended:1.7.8")

    implementation("org.mozilla.geckoview:geckoview:159.0.20261022150525")
    implementation("org.jsoup:jsoup:1.20.1")

    debugImplementation("androidx.compose.ui:ui-tooling:1.12.1")
}
