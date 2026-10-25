import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// Optional release signing: android/keystore.properties (gitignored). Absent => release stays unsigned.
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
val hasReleaseSigning = listOf("storeFile", "storePassword", "keyAlias", "keyPassword").all { !keystoreProps.getProperty(it).isNullOrBlank() }

// Release API base URL: -PsentinelApiBaseUrl=https://host/path. https only, trailing slashes are stripped.
// Unset keeps an invalid placeholder so an unconfigured release build fails visibly at startup.
val releaseApiBaseUrl: String = run {
    val raw = (project.findProperty("sentinelApiBaseUrl") as String?)?.trim()
    if (raw.isNullOrEmpty()) return@run "https://api.example.invalid"
    val url = raw.trimEnd('/')
    if (!url.startsWith("https://") || url.length <= "https://".length || url.any { it.isWhitespace() || it == '"' || it == '\\' }) {
        throw GradleException("sentinelApiBaseUrl must be an https:// URL without spaces or quotes (got '$raw')")
    }
    url
}

android {
    namespace = "ai.enthral.sentinel"
    compileSdk = 35
    buildToolsVersion = "35.0.0"

    defaultConfig {
        applicationId = "ai.enthral.sentinel"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // Only these locales ship; drives per-app language and trims library resources.
        androidResources.localeFilters += listOf("en", "hi")
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile")).takeIf { it.exists() }
                    ?: file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            // 10.0.2.2 = host loopback as seen from the emulator. Physical phone: `adb reverse tcp:8000 tcp:8000`
            // and override with -PsentinelApiBaseUrl=http://localhost:8000
            val url = (project.findProperty("sentinelApiBaseUrl") as String?) ?: "http://10.0.2.2:8000"
            buildConfigField("String", "API_BASE_URL", "\"$url\"")
            buildConfigField("boolean", "DEMO_ENABLED", "true")
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            buildConfigField("String", "API_BASE_URL", "\"$releaseApiBaseUrl\"")
            if (hasReleaseSigning) signingConfig = signingConfigs.getByName("release")
            // In-app demo mode posts sample scam messages to the signed-in account: never ship it.
            buildConfigField("boolean", "DEMO_ENABLED", "false")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
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

    lint {
        abortOnError = true
        checkReleaseBuilds = true
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    // Fakes shared by JVM unit tests and instrumented Compose tests.
    sourceSets {
        getByName("test").java.srcDir("src/sharedTest/java")
        getByName("androidTest").java.srcDir("src/sharedTest/java")
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        allWarningsAsErrors.set(false)
        freeCompilerArgs.add("-Xannotation-default-target=param-property")
    }
}

dependencies {
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    // Backdrop blur for the glass bars (Apache-2.0, github.com/chrisbanes/haze).
    implementation(libs.haze)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.lifecycle.viewmodel.savedstate)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.datastore.preferences)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)
    // Logging only exists in debug builds; release has a no-op in src/release.
    debugImplementation(libs.okhttp.logging)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.okhttp.logging)

    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.kotlinx.coroutines.test)
    debugImplementation(libs.compose.ui.test.manifest)
}
