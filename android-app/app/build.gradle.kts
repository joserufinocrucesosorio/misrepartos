import java.util.Properties

val firmaProps = rootProject.file("keystore.properties")
val firma = Properties().apply {
    if (firmaProps.exists()) firmaProps.inputStream().use { load(it) }
}

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "online.soymasdigital.jornada"
    compileSdk = 35

    defaultConfig {
        applicationId = "online.soymasdigital.jornada"
        minSdk = 26
        targetSdk = 35
        versionCode = 59
        versionName = "2.1.2"
    }

    signingConfigs {
        if (firmaProps.exists()) {
            create("release") {
                storeFile = file(firma.getProperty("storeFile"))
                storePassword = firma.getProperty("storePassword")
                keyAlias = firma.getProperty("keyAlias")
                keyPassword = firma.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (firmaProps.exists()) signingConfig = signingConfigs.getByName("release")
        }
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
}




