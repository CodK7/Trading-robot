plugins {
    alias(libs.plugins.android.application)
}

val releaseStorePath = providers.environmentVariable("AUTOFLOW_KEYSTORE_PATH").orNull
val releaseStorePassword = providers.environmentVariable("AUTOFLOW_KEYSTORE_PASSWORD").orNull
val releaseKeyAlias = providers.environmentVariable("AUTOFLOW_KEY_ALIAS").orNull
val releaseKeyPassword = providers.environmentVariable("AUTOFLOW_KEY_PASSWORD").orNull
val hasReleaseSigning = listOf(releaseStorePath, releaseStorePassword, releaseKeyAlias, releaseKeyPassword).all { !it.isNullOrBlank() }

android {
    namespace = "com.aistudio.autoflow.bxyp"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.aistudio.autoflow.bxyp"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.maybeCreate("release").apply {
                    storeFile = file(releaseStorePath!!)
                    storePassword = releaseStorePassword
                    keyAlias = releaseKeyAlias
                    keyPassword = releaseKeyPassword
                }
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    testImplementation(libs.junit)
}
