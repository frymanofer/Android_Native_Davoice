import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Optional, git-ignored: local.properties -> gemini.apiKey=... (see local.properties.example).
// The DaVoice demo license is public and lives in DemoConfig.kt.
val localProps = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}
val geminiApiKey: String = localProps.getProperty("gemini.apiKey", "")

android {
    namespace = "io.davoice.example"
    compileSdk = 34

    defaultConfig {
        applicationId = "io.davoice.example"
        minSdk = 29
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
        buildConfigField("String", "GEMINI_API_KEY", "\"$geminiApiKey\"")
        // The TTS and local-LLM native libraries ship arm64-v8a only.
        ndk { abiFilters += "arm64-v8a" }
    }

    // Two wake-word footprints. "full" uses the ACL (Arm Compute) build; "lite"
    // uses the low-footprint no-ACL build (CPU/XNNPACK) -> ~24 MB smaller APK.
    //   ./gradlew assembleFullDebug   -> full APK
    //   ./gradlew assembleLiteDebug   -> lite (low-footprint) APK
    flavorDimensions += "footprint"
    productFlavors {
        create("full") {
            dimension = "footprint"
        }
        create("lite") {
            dimension = "footprint"
            applicationIdSuffix = ".lite"
            versionNameSuffix = "-lite"
        }
    }

    buildFeatures {
        buildConfig = true
        viewBinding = true
    }

    buildTypes {
        release {
            // Kept off, like the React Native example. If you enable it, keep
            // com.davoice.** and ai.onnxruntime.** (see proguard-rules.pro).
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

    // Models are copied out of the APK at first launch; storing them
    // uncompressed makes that a straight copy.
    androidResources { noCompress += listOf("dm", "onnx", "json") }

    packaging {
        // More than one DaVoice AAR ships the C++ runtime.
        jniLibs {
            pickFirsts += "**/libc++_shared.so"
            // Extract .so files at install time, as the React Native example does
            // (android:extractNativeLibs="true").
            useLegacyPackaging = true
        }
        resources { excludes += "META-INF/**" }
    }
}

dependencies {
    // DaVoice native libraries (local Maven repo in ../libs, see settings.gradle.kts).
    // keyworddetection also provides ONNX Runtime, which the tts AAR uses at
    // runtime -- include it even if you only need TTS.
    // Wake word: ACL build for "full", no-ACL low-footprint build for "lite".
    "fullImplementation"("com.davoice:keyworddetection:1.0.0")
    "liteImplementation"("com.davoice:keyworddetection-lowfp:1.0.1")
    implementation("com.davoice:tts:1.0.0")
    implementation("com.davoice:llm:1.0.0")

    // The DaVoice .pom files declare no dependencies, so the ones the AARs
    // need at runtime are listed here explicitly.
    implementation("ai.picovoice:android-voice-processor:1.0.2")   // keyworddetection
    implementation("androidx.multidex:multidex:2.0.1")             // tts
    implementation("androidx.lifecycle:lifecycle-runtime:2.8.7")    // tts
    implementation("androidx.appcompat:appcompat:1.7.0")            // tts + app

    // App
    implementation("org.jetbrains.kotlin:kotlin-stdlib:2.1.20")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("com.google.android.material:material:1.12.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}
