plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.anezium.rokidlive.phone"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.anezium.rokidlive.phone"
        minSdk = 31
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
    }

    val helperApkAssetsDir = layout.buildDirectory.dir("generated/assets/glasses-helper/debug").get().asFile
    sourceSets.getByName("debug").assets.srcDir(helperApkAssetsDir)
    val copyGlassesHelperDebugApk by tasks.registering(Copy::class) {
        dependsOn(":glasses-helper:assembleDebug")
        from(project(":glasses-helper").layout.buildDirectory.file("outputs/apk/debug/glasses-helper-debug.apk"))
        into(helperApkAssetsDir)
        rename { "glasses-helper-debug.apk" }
    }
    tasks.matching { it.name == "mergeDebugAssets" }.configureEach {
        dependsOn(copyGlassesHelperDebugApk)
    }
}

dependencies {
    implementation(project(":shared"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.okhttp)
    implementation(libs.play.services.auth)
    implementation(libs.rokid.client.l)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
