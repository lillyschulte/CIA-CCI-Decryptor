plugins {
    id("com.android.application")
}

android {
    namespace = "io.github.lillyschulte.decryptor"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.lillyschulte.decryptor"
        minSdk = 30
        targetSdk = 36
        versionCode = 5
        versionName = "0.4.1"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Sideload-only app: sign release builds with the debug key
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        jniLibs {
            // ctrdecrypt/makerom are executables shipped as lib*.so; they must be
            // extracted to nativeLibraryDir so they can be run with ProcessBuilder
            useLegacyPackaging = true
        }
    }
}
