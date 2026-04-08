plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

val gitCommitCount = providers.exec {
    commandLine("git", "rev-list", "--count", "HEAD")
}.standardOutput.asText.map { it.trim().toInt() }

val gitCommitHashProvider = providers.exec {
    commandLine("git", "rev-parse", "--short", "HEAD")
}.standardOutput.asText.map { it.trim() }

val timestampVersionCode = providers.provider {
    (System.currentTimeMillis() / 1000L).toInt()
}

val unsignedReleaseBuild = providers.gradleProperty("unsignedRelease")
    .map { it.equals("true", ignoreCase = true) }
    .orElse(false)

android {
    namespace = "moe.fuqiuluo.mamu"
    compileSdk = 36

    defaultConfig {
        applicationId = "moe.fuqiuluo.mamu"
        minSdk = 24
        targetSdk = 35
        versionCode = timestampVersionCode.get()
        versionName = "1.0.1.r${gitCommitCount.get()}.${gitCommitHashProvider.get()}"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        getByName("debug")
        create("release") {
            val keystorePath = System.getenv("RELEASE_KEYSTORE_PATH")
                ?: file("keystore/release.keystore").absolutePath
            storeFile = file(keystorePath)
            storeType = "JKS"
            storePassword = System.getenv("RELEASE_KEYSTORE_PASSWORD") ?: "defaultPasswordNotForProduction"
            keyAlias = System.getenv("RELEASE_KEY_ALIAS") ?: "mamu_release"
            keyPassword = System.getenv("RELEASE_KEYSTORE_PASSWORD") ?: "defaultPasswordNotForProduction"
        }
    }

    buildTypes {
        release {
            if (!unsignedReleaseBuild.get()) {
                signingConfig = signingConfigs.getByName("release")
            }
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    sourceSets {
        getByName("main") {
            java.setSrcDirs(listOf("src/uiOnly/java"))
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    kotlinOptions {
        jvmTarget = "11"
    }

    buildFeatures {
        compose = true
        viewBinding = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.lifecycle.runtime.ktx)

    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.material)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
