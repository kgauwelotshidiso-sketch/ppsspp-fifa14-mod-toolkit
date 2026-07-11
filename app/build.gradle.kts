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
        versionCode = 1
        versionName = "0.1.0-phase1"
    }

    buildTypes {
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
