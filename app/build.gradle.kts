import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// version.properties is the canonical version state.
val versionProps = Properties().apply {
    rootProject.file("version.properties").inputStream().use { load(it) }
}
fun v(key: String) = versionProps.getProperty(key).toInt()
// The central release publisher passes the Play-safe code/name; local builds derive them.
fun override(key: String) = (findProperty(key) as String?)?.takeIf { it.isNotBlank() }

android {
    namespace = "com.hereliesaz.clappiest"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.hereliesaz.clappiest"
        minSdk = 26
        targetSdk = 37
        versionCode = override("versionCodeOverride")?.toInt()
            ?: (v("versionMajor") * 1_000_000 + v("versionMinor") * 10_000 +
                v("versionPatch") * 100 + v("versionBuild"))
        versionName = override("versionNameOverride")
            ?: "${v("versionMajor")}.${v("versionMinor")}.${v("versionPatch")}"
    }

    buildTypes {
        release {
            // R8 on: the Play publisher requires mapping.txt.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    kotlinOptions {
        jvmTarget = "21"
    }
    buildFeatures {
        compose = true
    }
    ndkVersion = "29.0.14206865"
}

dependencies {
    val composeBom = platform(libs.androidx.compose.bom)
    implementation(composeBom)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core.ktx)
    testImplementation(libs.junit)
}
