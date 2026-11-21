plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.kapt")
}

android {
    namespace = "com.myokooo.ceir"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.myokooo.ceir"
        minSdk = 24
        targetSdk = 34
        versionCode = 4
        versionName = "1.2.1"
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }

        // Supabase — build-time secrets (env SUPABASE_URL / SUPABASE_KEY, or gradle property)
        val supabaseUrl: String =
            System.getenv("SUPABASE_URL") ?: (findProperty("SUPABASE_URL") as String? ?: "")
        val supabaseKey: String =
            System.getenv("SUPABASE_KEY") ?: (findProperty("SUPABASE_KEY") as String? ?: "")
        buildConfigField("String", "SUPABASE_URL", "\"$supabaseUrl\"")
        buildConfigField("String", "SUPABASE_KEY", "\"$supabaseKey\"")
    }

    signingConfigs {
        create("release") {
            val keystorePath = System.getenv("KEYSTORE_PATH") ?: "release.jks"
            storeFile = file(keystorePath)
            if (keystorePath.endsWith(".p12") || keystorePath.endsWith(".pfx")) {
                storeType = "PKCS12"
            }
            storePassword = System.getenv("KEYSTORE_PASSWORD") ?: ""
            keyAlias = System.getenv("KEY_ALIAS") ?: ""
            keyPassword = System.getenv("KEY_PASSWORD") ?: ""
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { viewBinding = true; buildConfig = true }

    packaging {
        resources {
            excludes += setOf(
                "META-INF/DEPENDENCIES", "META-INF/LICENSE", "META-INF/LICENSE.txt",
                "META-INF/license.txt", "META-INF/NOTICE", "META-INF/NOTICE.txt",
                "META-INF/notice.txt", "META-INF/*.kotlin_module",
                "META-INF/AL2.0", "META-INF/LGPL2.1",
                "META-INF/INDEX.LIST", "META-INF/io.netty.versions.properties"
            )
        }
    }
}

dependencies {
    // ============================================================
    // AndroidX Core
    // ============================================================
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")

    // ============================================================
    // Material 3 — CardView
    // ============================================================
    implementation("androidx.cardview:cardview:1.0.0")

    // ============================================================
    // Lifecycle & Coroutines
    // ============================================================
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.7.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    // ============================================================
    // CameraX
    // ============================================================
    val cameraxVersion = "1.3.1"
    implementation("androidx.camera:camera-core:$cameraxVersion")
    implementation("androidx.camera:camera-camera2:$cameraxVersion")
    implementation("androidx.camera:camera-lifecycle:$cameraxVersion")
    implementation("androidx.camera:camera-view:$cameraxVersion")

    // ============================================================
    // ML Kit — Barcode + Text Recognition (OCR)
    // ============================================================
    implementation("com.google.mlkit:barcode-scanning:17.2.0")
    implementation("com.google.mlkit:text-recognition:16.0.0")

    // ============================================================
    // Room Database
    // ============================================================
    val roomVersion = "2.6.1"
    implementation("androidx.room:room-runtime:$roomVersion")
    implementation("androidx.room:room-ktx:$roomVersion")
    kapt("androidx.room:room-compiler:$roomVersion")

    // ============================================================
    // WorkManager — Sync
    // ============================================================
    implementation("androidx.work:work-runtime-ktx:2.9.0")

    // ============================================================
    // DataStore
    // ============================================================
    implementation("androidx.datastore:datastore-preferences:1.0.0")

    // ============================================================
    // HTTP Client — Supabase REST API
    // ============================================================
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // ============================================================
    // JSON
    // ============================================================
    implementation("org.json:json:20231013")
}
