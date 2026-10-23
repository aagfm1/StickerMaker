plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Authority of the ContentProvider that WhatsApp reads sticker packs from.
// Must be unique on the device — change it together with applicationId if you rename the app.
val stickerAuthority = "com.stickermaker.app.stickercontentprovider"

android {
    namespace = "com.stickermaker.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.stickermaker.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 2
        versionName = "1.1"

        manifestPlaceholders["contentProviderAuthority"] = stickerAuthority
        buildConfigField("String", "CONTENT_PROVIDER_AUTHORITY", "\"$stickerAuthority\"")
    }

    // A fixed key kept in the repo, so every build installs as an update over the last one.
    // Fine for personal use; before publishing on Google Play, create a private key and keep it out of git.
    signingConfigs {
        create("fixed") {
            storeFile = file("sticker-release.keystore")
            storePassword = "stickermaker"
            keyAlias = "sticker"
            keyPassword = "stickermaker"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("fixed")
        }
    }

    buildFeatures {
        buildConfig = true
        viewBinding = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.exifinterface:exifinterface:1.3.7")
    implementation("com.google.android.material:material:1.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    // Small, standalone GIF frame decoder (from Glide).
    implementation("com.github.bumptech.glide:gifdecoder:4.16.0")
}
