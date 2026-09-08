plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.voxwolf.dictation"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.voxwolf.dictation"
        minSdk = 31
        targetSdk = 31
        versionCode = 1
        versionName = "4.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            abiFilters += "arm64-v8a"
        }

        // Model SHA-256 digest — replace PLACEHOLDER with real digest after committing model via Git LFS
        buildConfigField(
            "String",
            "MODEL_SHA256",
            "\"${project.findProperty("modelSha256") ?: libs.versions.modelSha256.get()}\""
        )
    }

    flavorDimensions += "channel"
    productFlavors {
        create("release") {
            dimension = "channel"
        }
        create("harness") {
            dimension = "channel"
            applicationIdSuffix = ".harness"
        }
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        getByName("debug") {
            isMinifyEnabled = false
        }
    }

    // Harness variant is always debuggable with no minification
    applicationVariants.all {
        if (flavorName == "harness") {
            buildType.isMinifyEnabled.let {
                // Harness: debuggable, no minify (handled by buildType debug)
            }
        }
    }

    sourceSets {
        getByName("harness") {
            java.srcDirs("src/harness/java")
            manifest.srcFile("src/harness/AndroidManifest.xml")
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

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    ndkVersion = "26.1.10909125"

    androidResources {
        noCompress += "bin"
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
}
