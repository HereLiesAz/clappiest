import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// version.properties is the canonical version state.
val versionProps = Properties().apply {
    rootProject.file("version.properties").inputStream().use { load(it) }
}
fun v(key: String) = versionProps.getProperty(key).toInt()

android {
    namespace = "com.hereliesaz.clappiest"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.hereliesaz.clappiest"
        minSdk = 26
        targetSdk = 35
        versionCode = v("versionMajor") * 1_000_000 + v("versionMinor") * 10_000 +
            v("versionPatch") * 100 + v("versionBuild")
        versionName = "${v("versionMajor")}.${v("versionMinor")}.${v("versionPatch")}"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
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
        compose = true
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.core:core-ktx:1.15.0")
    testImplementation("junit:junit:4.13.2")
}
