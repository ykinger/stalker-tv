import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}
val localSettings = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}
fun buildString(value: String) = "\"${value.replace("\\", "\\\\").replace("\"", "\\\"")}\""
android {
    namespace = "com.ykinger.stalkertv"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.ykinger.stalkertv"
        minSdk = 26
        targetSdk = 35
        versionCode = 2
        versionName = "0.1.0-alpha.1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        listOf("HOST", "MAC", "SERIAL", "ADID", "RANDOM", "HW_VERSION_2", "HANDSHAKE_PREHASH", "PROFILE_PREHASH")
            .forEach { buildConfigField("String", "CAPTURED_$it", buildString("")) }
    }
    buildTypes {
        getByName("debug") {
            mapOf(
                "HOST" to "host", "MAC" to "mac", "SERIAL" to "serial", "ADID" to "adid",
                "RANDOM" to "random", "HW_VERSION_2" to "hw_version_2",
                "HANDSHAKE_PREHASH" to "handshake_prehash", "PROFILE_PREHASH" to "profile_prehash"
            ).forEach { (field, property) ->
                buildConfigField("String", "CAPTURED_$field",
                    buildString(localSettings.getProperty("tivimate.$property", "")))
            }
        }
    }
    buildFeatures { compose = true; buildConfig = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    testOptions { unitTests.isIncludeAndroidResources = true }
}
kotlin { jvmToolchain(17) }
dependencies {
    implementation(project(":core"))
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.icons)
    implementation(libs.compose.foundation)
    implementation(libs.tv.material)
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.navigation.compose)
    implementation(libs.coroutines.android)
    implementation(libs.okhttp)
    implementation(libs.gson)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.exoplayer.hls)
    implementation(libs.media3.ui)
    debugImplementation(libs.compose.tooling)
    debugImplementation(libs.compose.test.manifest)
    testImplementation(libs.junit)
    testImplementation(libs.mockwebserver)
    testImplementation(libs.json.test)
    testImplementation(libs.coroutines.test)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.test)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.runner)
}
