import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Release-Signatur aus android/keystore.properties (gitignored, zeigt auf
// ~/.config/tablet-agent/release.jks). Fehlt sie, wird Release unsigniert gebaut.
val ksDatei = rootProject.file("keystore.properties")
val ks = Properties().apply { if (ksDatei.exists()) ksDatei.inputStream().use { load(it) } }

android {
    namespace = "de.zerties.tabletagent"
    compileSdk = 34

    defaultConfig {
        applicationId = "de.zerties.tabletagent"
        // Fire OS 5 = Android 5.1 (API 22). targetSdk bewusst 22: genau das
        // Verhalten der Tablets, Berechtigungen wie WRITE_SETTINGS gelten ab Installation.
        minSdk = 21
        targetSdk = 22
        versionCode = 7
        versionName = "0.2.2"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    signingConfigs {
        if (ksDatei.exists()) {
            create("release") {
                storeFile = file(ks.getProperty("storeFile"))
                storePassword = ks.getProperty("storePassword")
                keyAlias = ks.getProperty("keyAlias")
                keyPassword = ks.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.findByName("release")
            isMinifyEnabled = false
        }
    }

    lint {
        // Altes targetSdk ist Absicht (nur Fire OS 5, kein Play Store)
        disable += setOf("ExpiredTargetSdkVersion", "OldTargetApi")
        checkReleaseBuilds = false
    }
}

dependencies {
    // reines Java, laeuft auf Android 5
    implementation("org.eclipse.paho:org.eclipse.paho.client.mqttv3:1.2.5")
}
