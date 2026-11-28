plugins { id("com.android.application") }
android {
    namespace = "com.veluvara.translatorlab"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.veluvara.translatorlab"
        minSdk = 26
        targetSdk = 35
        versionCode = 11
        versionName = "0.8.1-preview"
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
}

dependencies { implementation("com.google.mlkit:translate:17.0.3") }

dependencies { implementation("org.eclipse.paho:org.eclipse.paho.client.mqttv3:1.2.5") }
