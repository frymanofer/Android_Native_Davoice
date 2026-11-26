plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// The DaVoice: AI Car Voice Agent: wake word -> (optional) speaker verification -> speech to text -> the DaVoice Automotive Agent (a
// fine-tuned 0.6B model on the phone) -> actions on a SIMULATED car -> text to speech. The same app as the React Native and
// Flutter example_car_agent; it never controls a real vehicle.
android {
    namespace = "io.davoice.caragent"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.davoice.caragent"
        minSdk = 29
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
        // The TTS and local-LLM native libraries ship arm64-v8a only.
        ndk { abiFilters += "arm64-v8a" }
    }

    // The two wake-word footprints, as in the demo app (../app): "full" = ACL build, "lite" = no ACL, ~24 MB smaller.
    flavorDimensions += "footprint"
    productFlavors {
        create("full") { dimension = "footprint" }
        create("lite") {
            dimension = "footprint"
            applicationIdSuffix = ".lite"
            versionNameSuffix = "-lite"
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    androidResources { noCompress += listOf("dm", "onnx", "json") }

    packaging {
        jniLibs {
            pickFirsts += "**/libc++_shared.so"
            useLegacyPackaging = true
        }
        resources { excludes += "META-INF/**" }
    }

    // The unit tests read the shared fixtures and the car's JSON files from the assets.
    testOptions { unitTests.isReturnDefaultValues = true }
}

dependencies {
    "fullImplementation"("com.davoice:keyworddetection:1.0.15")
    "liteImplementation"("com.davoice:keyworddetection-lowfp:1.0.5")
    implementation("com.davoice:tts:1.0.0")
    implementation("com.davoice:llm:1.0.0")

    // What the DaVoice AARs need at runtime (their .pom files declare no dependencies).
    implementation("ai.picovoice:android-voice-processor:1.0.2")
    implementation("androidx.multidex:multidex:2.0.1")
    implementation("androidx.lifecycle:lifecycle-runtime:2.8.7")
    implementation("androidx.appcompat:appcompat:1.7.0")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
}
