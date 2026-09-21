plugins {
    id("com.android.application")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.elina.assistant"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.elina.assistant"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    buildFeatures { viewBinding = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    signingConfigs {
        create("elinaRelease") {
            val path = System.getenv("ELINA_KEYSTORE_PATH")
            val storePass = System.getenv("ELINA_KEYSTORE_PASSWORD")
            val alias = System.getenv("ELINA_KEY_ALIAS")
            val keyPass = System.getenv("ELINA_KEY_PASSWORD")
            if (!path.isNullOrBlank() && !storePass.isNullOrBlank() && !alias.isNullOrBlank() && !keyPass.isNullOrBlank()) {
                storeFile = file(path); storePassword = storePass; keyAlias = alias; keyPassword = keyPass
            }
        }
    }
    buildTypes {
        debug { isMinifyEnabled = false }
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = if (System.getenv("ELINA_KEYSTORE_PATH").isNullOrBlank()) signingConfigs.getByName("debug") else signingConfigs.getByName("elinaRelease")
        }
    }
    packaging { resources.excludes += setOf("META-INF/AL2.0", "META-INF/LGPL2.1") }
}

dependencies {
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("com.google.android.material:material:1.13.0")
    implementation("androidx.constraintlayout:constraintlayout:2.2.1")
    implementation("androidx.activity:activity-ktx:1.10.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.9.2")
    implementation("androidx.datastore:datastore-preferences:1.1.7")
    implementation("androidx.room:room-runtime:2.7.2")
    implementation("androidx.room:room-ktx:2.7.2")
    ksp("androidx.room:room-compiler:2.7.2")
    implementation("androidx.webkit:webkit:1.14.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}
