plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    kotlin("kapt")
}

android {
    namespace = "com.openingtip"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.openingtip"
        minSdk = 29
        targetSdk = 35
        versionCode = 2
        versionName = "1.0.1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("release") {
            storeFile = file("openingtip.jks")
            storePassword = "hyperintell2026"
            keyAlias = "openingtip"
            keyPassword = "hyperintell2026"
        }
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
        getByName("debug") {
            signingConfig = signingConfigs.getByName("release")
        }
    }

    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }

    flavorDimensions += "mode"
    productFlavors {
        create("consumer") {
            dimension = "mode"
            // 普通安装版
        }
        create("managed") {
            dimension = "mode"
            // 受管设备专用版
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    kotlinOptions {
        jvmTarget = "21"
    }

    buildFeatures {
        compose = true
    }

    sourceSets {
        getByName("main") {
            kotlin.srcDirs(
                "src/main/kotlin",
                "../core/model/src/main/kotlin",
                "../core/domain/src/main/kotlin",
                "../core/database/src/main/kotlin",
                "../core/platform/src/main/kotlin",
                "../core/security/src/main/kotlin",
                "../feature/onboarding/src/main/kotlin",
                "../feature/gate/src/main/kotlin",
                "../feature/launcher/src/main/kotlin",
                "../feature/settings/src/main/kotlin",
                "../data/usage/src/main/kotlin",
                "../enforcement/consumer/src/main/kotlin",
                "../enforcement/managed/src/main/kotlin"
            )
        }
        getByName("consumer") {
            manifest.srcFile("src/consumer/AndroidManifest.xml")
        }
        getByName("managed") {
            manifest.srcFile("src/managed/AndroidManifest.xml")
            res.srcDirs("src/managed/res")
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.navigation.compose)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    kapt(libs.androidx.room.compiler)

    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
