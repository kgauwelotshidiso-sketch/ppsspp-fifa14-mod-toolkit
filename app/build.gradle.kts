plugins {
    id("com.android.application")
}

android {
    namespace = "com.tshidiso.ppssppmodtoolkit"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.tshidiso.ppssppmodtoolkit"
        minSdk = 26
        targetSdk = 36
        versionCode = 8
        versionName = "0.7.0-phase1g"
    }

    signingConfigs {
        create("stableDevelopment") {
            storeFile = rootProject.file("keystore/ppsspp-toolkit-development.p12")
            storeType = "PKCS12"
            storePassword = "phase1bdebug"
            keyAlias = "ppssppmodtoolkitdebug"
            keyPassword = "phase1bdebug"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("stableDevelopment")
        }
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        abortOnError = true
        checkReleaseBuilds = false
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}
