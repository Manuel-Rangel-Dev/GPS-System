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
        versionCode = 4
        versionName = "1.3"

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
    // Android/Kotlin Basics
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.cardview)

    // Google Play Services - Location (FusedLocationProviderClient)
    implementation(libs.play.services.location)

    // Coroutines (for handling location asynchronously and in an ordered manner)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.play.services)

    implementation(libs.osmdroid)

    testImplementation(libs.json)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
