import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Release signing: read from keystore.properties (local) or environment variables (CI).
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun signingValue(key: String, env: String): String? =
    keystoreProps.getProperty(key) ?: System.getenv(env)

android {
    namespace = "com.glowled.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.glowled.app"
        minSdk = 29
        targetSdk = 35
        versionCode = 20
        versionName = "2.1.0"
    }

    signingConfigs {
        create("release") {
            val storePath = signingValue("storeFile", "GLOWLED_KEYSTORE")
            if (storePath != null && file(storePath).exists()) {
                storeFile = file(storePath)
                storePassword = signingValue("storePassword", "GLOWLED_KEYSTORE_PASSWORD")
                keyAlias = signingValue("keyAlias", "GLOWLED_KEY_ALIAS")
                keyPassword = signingValue("keyPassword", "GLOWLED_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            val release = signingConfigs.getByName("release")
            signingConfig = if (release.storeFile != null) release else signingConfigs.getByName("debug")
        }
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        buildConfig = true
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.palette:palette-ktx:1.0.0")
    implementation("androidx.viewpager2:viewpager2:1.1.0")
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")

    testImplementation("junit:junit:4.13.2")
}
