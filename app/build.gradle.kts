import java.util.Properties
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}
val keys = Properties().apply { rootProject.file("key.properties").inputStream().use { load(it) } }
android {
    namespace = "com.red.note"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.red.note"
        minSdk = 24
        targetSdk = 36
        versionCode = 76
        versionName = "1.1.8"
        ndk { abiFilters += "arm64-v8a" }
    }
    buildFeatures { compose = true; buildConfig = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    signingConfigs {
        create("release") {
            storeFile = rootProject.file(System.getenv("REDNOTE_STORE_FILE") ?: keys.getProperty("storeFile"))
            storeType = System.getenv("REDNOTE_STORE_TYPE") ?: keys.getProperty("storeType", "BKS")
            storePassword = keys.getProperty("storePassword")
            keyAlias = keys.getProperty("keyAlias")
            keyPassword = keys.getProperty("keyPassword")
        }
    }
    testOptions { unitTests.isIncludeAndroidResources = true }
    buildTypes { release { signingConfig = signingConfigs.getByName("release"); isMinifyEnabled = false } }
}
kotlin { compilerOptions { jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17 } }
dependencies {
    implementation(platform("androidx.compose:compose-bom:2025.09.01"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.10.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.10.0")
    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("io.coil-kt:coil-gif:2.7.0")
    implementation("com.squareup.okhttp3:okhttp:5.4.0")
    implementation("androidx.media3:media3-exoplayer:1.9.2")
    implementation("androidx.media3:media3-ui:1.9.2")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.16")
    testImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    testImplementation("org.json:json:20240303")
}

