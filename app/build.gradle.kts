plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.uninorte.locator"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.uninorte.locator"
        minSdk = 24
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    // Base de Android/Kotlin
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.cardview)

    // Google Play Services - Ubicación (FusedLocationProviderClient)
    implementation(libs.play.services.location)

    // Coroutines (para manejar la ubicación de forma asíncrona y ordenada)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.play.services)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
