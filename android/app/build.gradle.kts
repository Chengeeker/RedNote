import java.util.Properties
plugins {
    id("com.android.application")
    id("dev.flutter.flutter-gradle-plugin")
}
val keys = Properties().apply { rootProject.file("key.properties").inputStream().use { load(it) } }
android {
    namespace = "com.red.note"
    compileSdk = flutter.compileSdkVersion
    ndkVersion = flutter.ndkVersion
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    defaultConfig {
        applicationId = "com.red.note"
        minSdk = 24
        targetSdk = flutter.targetSdkVersion
        versionCode = flutter.versionCode
        versionName = flutter.versionName
        ndk { abiFilters += listOf("arm64-v8a") }
    }
    signingConfigs {
        create("release") {
            storeFile = rootProject.file(System.getenv("REDNOTE_STORE_FILE") ?: keys.getProperty("storeFile"))
            storeType = System.getenv("REDNOTE_STORE_TYPE") ?: keys.getProperty("storeType", "JKS")
            storePassword = keys.getProperty("storePassword")
            keyAlias = keys.getProperty("keyAlias")
            keyPassword = keys.getProperty("keyPassword")
        }
    }
    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = false
            isShrinkResources = false
        }
    }
    packaging {
        jniLibs.excludes += setOf("**/armeabi-v7a/**", "**/x86/**", "**/x86_64/**")
    }
}
kotlin { compilerOptions { jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17 } }
flutter { source = "../.." }
dependencies { implementation("com.squareup.okhttp3:okhttp:5.4.0") }
