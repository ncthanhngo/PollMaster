plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.ncthanhngo.pollmaster"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.ncthanhngo.pollmaster"
        minSdk = 26
        targetSdk = 35
        // release-apk.sh passes the commit count, like the iOS build number
        versionCode = (findProperty("versionCode") as String?)?.toInt() ?: 1
        versionName = "1.0.0"
    }

    // Release key lives outside the repo; release-apk.sh exports these from the keychain.
    val keystore = System.getenv("POLLMASTER_KEYSTORE")?.let(::file)?.takeIf { it.isFile }
    signingConfigs {
        if (keystore != null) create("release") {
            storeFile = keystore
            storePassword = System.getenv("POLLMASTER_KEYSTORE_PASSWORD")
            keyAlias = System.getenv("POLLMASTER_KEY_ALIAS") ?: "pollmaster"
            keyPassword = System.getenv("POLLMASTER_KEYSTORE_PASSWORD")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    sourceSets["main"].assets.srcDir("../../shared/templates")
    sourceSets["main"].assets.srcDir(layout.buildDirectory.dir("generated/guideAssets"))
    sourceSets["main"].java.srcDir("src/main/kotlin")
    sourceSets["test"].java.srcDir("src/test/kotlin")

    lint {
        abortOnError = true
        checkReleaseBuilds = false
    }
}

kotlin {
    jvmToolchain(17)
}

// Stages only the AI guide from ../shared into an assets dir (the file itself is not duplicated in the repo).
val stageGuideAsset by tasks.registering(Sync::class) {
    from("../../shared") { include("huong-dan-tao-mau-bang-ai.md") }
    into(layout.buildDirectory.dir("generated/guideAssets"))
}
tasks.matching { it.name.startsWith("merge") && it.name.endsWith("Assets") }.configureEach { dependsOn(stageGuideAsset) }
tasks.matching { it.name.startsWith("lintVital") || it.name.startsWith("lintAnalyze") || it.name.startsWith("generate") && it.name.endsWith("LintReportModel") }.configureEach { dependsOn(stageGuideAsset) }

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.exifinterface)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.coil.compose)
    implementation(libs.mlkit.text)
    implementation(libs.osmdroid)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
