import java.util.Properties

plugins {
    id("com.android.application")
}

android {
    namespace = "app.spinbridge"
    compileSdk = 36

    defaultConfig {
        applicationId = "app.discoauto"
        // 31 = Android 12.
        minSdk = 31
        targetSdk = 36
        versionCode = 5
        versionName = "0.5.0"
    }

    // Release signing. The key and its passwords live outside the repository: keystore.properties
    // (git-ignored, next to settings.gradle.kts) names the keystore file and holds the passwords.
    // Without it, release builds come out unsigned.
    val keystoreFile = rootProject.file("keystore.properties")
    if (keystoreFile.exists()) {
        val keystore = Properties().apply { keystoreFile.inputStream().use { load(it) } }
        signingConfigs {
            create("release") {
                storeFile = file(keystore.getProperty("storeFile"))
                storePassword = keystore.getProperty("storePassword")
                keyAlias = keystore.getProperty("keyAlias")
                keyPassword = keystore.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // The Android Auto receiver.
    implementation("com.google.protobuf:protobuf-java:3.25.5")
    implementation("org.conscrypt:conscrypt-android:2.5.3")

    testImplementation("junit:junit:4.13.2")
}
