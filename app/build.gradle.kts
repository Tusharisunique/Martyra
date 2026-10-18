import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

val localProps = Properties().also { p ->
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use(p::load)
}

android {
    namespace = "com.martyra.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.martyra.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"

        buildConfigField("String", "SUPABASE_URL",
            "\"${localProps.getProperty("supabase.url", "")}\"")
        buildConfigField("String", "SUPABASE_KEY",
            "\"${localProps.getProperty("supabase.key", "")}\"")
        // Release SERVER_URL must be supplied via local.properties; debug falls back to localhost.
        buildConfigField("String", "SERVER_URL",
            "\"${localProps.getProperty("server.url.release", "")}\"")
    }

    buildTypes {
        debug {
            buildConfigField("String", "SERVER_URL",
                "\"${localProps.getProperty("server.url.debug", "http://localhost:3000")}\"")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    sourceSets {
        getByName("debug") {
            res.srcDirs("src/debug/res")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    val composeBom = platform(libs.compose.bom)
    implementation(composeBom)
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.activity.compose)

    implementation(libs.camera.core)
    implementation(libs.camera.camera2)
    implementation(libs.camera.lifecycle)
    implementation(libs.camera.video)
    implementation(libs.camera.view)

    implementation(libs.lifecycle.service)
    implementation(libs.work.runtime.ktx)
    implementation(libs.sqlcipher)
    implementation(libs.androidx.sqlite)
}
