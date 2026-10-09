plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.example.arsurvey"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.arsurvey"
        minSdk = 26          // ARCore Depth realistically targets newer devices; simplifies image APIs
        targetSdk = 34
        versionCode = 1
        versionName = "0.7-m7"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    buildFeatures {
        compose = true
    }
    composeOptions {
        // Compose compiler that matches Kotlin 1.9.25
        kotlinCompilerExtensionVersion = "1.5.15"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    androidResources {
        // ONNX models must stay uncompressed in the APK so ONNX Runtime can mmap them
        // directly from assets (the large EfficientSAM encoder especially). Without this the
        // aapt-compressed asset can't be memory-mapped and load is slower / can OOM.
        noCompress += "onnx"
    }
}

dependencies {
    // Compose
    val composeBom = platform("androidx.compose:compose-bom:2024.09.02")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.6")

    // AR rendering — SceneView (bundles ARCore + Filament) with the Compose ARScene API
    implementation("io.github.sceneview:arsceneview:2.2.1")

    // On-device inference for YOLO11-Seg. The -android AAR bundles the NNAPI execution
    // provider (hardware accel on the Nord 5) with a CPU fallback.
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.20.0")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // Room — local persistence for the capture-first catalog (objects + photo metadata). Images and
    // raw depth stay as files on disk; Room stores their paths plus the ARCore metadata for retrieval
    // and later analysis. room-ktx brings coroutine/Flow support; room-compiler runs under KSP.
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-tooling-preview")

    // Unit tests for pure logic (target tracking, isolation). JVM only — no device needed.
    testImplementation("junit:junit:4.13.2")
}
