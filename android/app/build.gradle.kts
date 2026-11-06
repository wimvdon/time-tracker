import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// ---------------------------------------------------------------------------
// App identity: the ONE place to change the package / application ID. Phones treat a
// different ID as a different app (no update, no data carried over), so never change it
// again. It was com.impca201.notiontimetracker up to v1.8.x (see docs/decisions.md).
// ---------------------------------------------------------------------------
val appId = "to.wim.timetracker"

// ---------------------------------------------------------------------------
// Versioning
//  - Release builds: the release workflow passes -PappVersionName=X.Y.Z and
//    -PappVersionCode=N (N = major*10000 + minor*100 + patch).
//  - Every other build: version comes from `git describe --tags --long`, e.g.
//    1.2.0-3-gabc1234, so a dev build never looks like a release.
//    Falls back to 0.0.0-dev when there are no tags or no git.
// ---------------------------------------------------------------------------
val releaseVersionName: String? = providers.gradleProperty("appVersionName").orNull
val releaseVersionCode: String? = providers.gradleProperty("appVersionCode").orNull

fun gitDescribeVersion(): String {
    val fallback = "0.0.0-dev"
    return try {
        val result =
            providers.exec {
                commandLine("git", "describe", "--tags", "--long", "--dirty", "--match", "v[0-9]*")
                isIgnoreExitValue = true
            }
        if (result.result.get().exitValue != 0) {
            fallback
        } else {
            result.standardOutput.asText.get().trim().removePrefix("v").ifEmpty { fallback }
        }
    } catch (e: Exception) {
        // git is not installed or this is not a git checkout (e.g. a downloaded source zip).
        fallback
    }
}

data class AppVersion(val name: String, val code: Int)

val appVersion: AppVersion =
    if (releaseVersionName != null || releaseVersionCode != null) {
        require(releaseVersionName != null && releaseVersionCode != null) {
            "Pass both -PappVersionName and -PappVersionCode, or neither."
        }
        require(Regex("""\d+\.\d+\.\d+""").matches(releaseVersionName)) {
            "appVersionName must look like X.Y.Z, got '$releaseVersionName'."
        }
        val code =
            releaseVersionCode.toIntOrNull()?.takeIf { it > 0 }
                ?: error("appVersionCode must be a positive number, got '$releaseVersionCode'.")
        AppVersion(releaseVersionName, code)
    } else {
        AppVersion(gitDescribeVersion(), 1)
    }

// ---------------------------------------------------------------------------
// Release signing: only when keystore.properties exists. The release workflow
// writes it at build time and deletes it afterwards. Never commit it.
// ---------------------------------------------------------------------------
val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties =
    Properties().apply {
        if (keystorePropertiesFile.exists()) keystorePropertiesFile.inputStream().use { load(it) }
    }

android {
    namespace = appId
    compileSdk = 37

    defaultConfig {
        applicationId = appId
        // Android 8.0. Lower than the template's Android 12: see docs/decisions.md.
        minSdk = 26
        targetSdk = 37
        versionCode = appVersion.code
        versionName = appVersion.name
        // The app link "Sign in with Notion" comes back on (AndroidManifest.xml, worker/src/auth.js).
        manifestPlaceholders["authScheme"] = "timetrackerfornotion"
    }

    signingConfigs {
        if (keystorePropertiesFile.exists()) {
            create("release") {
                storeFile = file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            // Lets a pull request's debug APK be installed next to the released app, as
            // "Time Tracker Dev" (src/debug/res), with its own settings and API key.
            applicationIdSuffix = ".debug"
            // Its own sign-in link, so signing in on the Dev build never opens the released app.
            manifestPlaceholders["authScheme"] = "timetrackerfornotion-debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfig = signingConfigs.findByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    androidResources {
        localeFilters += listOf("en", "nl", "fr", "de", "es", "it")
    }

    lint {
        abortOnError = true
        checkDependencies = true
        // Do not disable checks or add a baseline without recording why in docs/decisions.md.
    }

    dependenciesInfo {
        // Not needed outside Google Play; keeps the APK free of an encrypted dependency blob.
        includeInApk = false
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.security.crypto)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.graphics.shapes)
    implementation(libs.androidx.compose.material.icons)

    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit)
}

// Used by the workflows to name the APK <app>-<version>.apk: ./gradlew -q :app:printApkBaseName
tasks.register("printApkBaseName") {
    val baseName = "${rootProject.name}-${appVersion.name}"
    doLast { println(baseName) }
}
