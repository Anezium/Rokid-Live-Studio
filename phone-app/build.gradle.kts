import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

val secretProperties = Properties().apply {
    val file = rootProject.file("secrets.properties")
    if (file.isFile) file.inputStream().use(::load)
}

fun signingValue(name: String): String? =
    providers.gradleProperty(name).orNull
        ?: providers.environmentVariable(name).orNull
        ?: secretProperties.getProperty(name)

val releaseStoreFilePath = signingValue("RLS_RELEASE_STORE_FILE")
val releaseStorePassword = signingValue("RLS_RELEASE_STORE_PASSWORD")
val releaseKeyAlias = signingValue("RLS_RELEASE_KEY_ALIAS")
val releaseKeyPassword = signingValue("RLS_RELEASE_KEY_PASSWORD")
val hasReleaseSigning = listOf(
    releaseStoreFilePath,
    releaseStorePassword,
    releaseKeyAlias,
    releaseKeyPassword
).all { !it.isNullOrBlank() }

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

    signingConfigs {
        create("release") {
            if (hasReleaseSigning) {
                storeFile = rootProject.file(releaseStoreFilePath!!)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        release {
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    listOf("debug", "release").forEach { buildTypeName ->
        val variantName = buildTypeName.replaceFirstChar { it.uppercaseChar() }
        val helperApkAssetsDir = layout.buildDirectory.dir("generated/assets/glasses-helper/$buildTypeName").get().asFile
        val helperApkName = when {
            buildTypeName == "debug" -> "glasses-helper-debug.apk"
            hasReleaseSigning -> "glasses-helper-release.apk"
            else -> "glasses-helper-release-unsigned.apk"
        }
        sourceSets.getByName(buildTypeName).assets.srcDir(helperApkAssetsDir)
        val copyHelperApk = tasks.register<Copy>("copyGlassesHelper${variantName}Apk") {
            dependsOn(":glasses-helper:assemble$variantName")
            from(project(":glasses-helper").layout.buildDirectory.file("outputs/apk/$buildTypeName/$helperApkName"))
            into(helperApkAssetsDir)
            rename { "glasses-helper-debug.apk" }
        }
        val generatedAssetConsumers = setOf(
            "merge${variantName}Assets",
            "generate${variantName}LintVitalReportModel",
            "lintVitalAnalyze${variantName}"
        )
        tasks.matching { it.name in generatedAssetConsumers }.configureEach {
            dependsOn(copyHelperApk)
        }
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
