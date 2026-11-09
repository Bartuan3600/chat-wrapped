import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// "Ganzen Chat analysieren" defaults (ntfy.sh + AES-256-GCM, like Ausgaben "Belege scannen").
// Read from analysis-config.json in the project root (git-ignored, see analysis-config.example.json) or $CW_ANALYSIS_CONFIG.
val cwConfig: Map<String, String> = run {
    val f = file(System.getenv("CW_ANALYSIS_CONFIG") ?: rootProject.file("analysis-config.json").path)
    if (!f.exists()) return@run emptyMap()
    @Suppress("UNCHECKED_CAST")
    (groovy.json.JsonSlurper().parse(f) as Map<String, Any?>).mapValues { it.value?.toString() ?: "" }
}
fun cwConst(key: String) = "\"" + (cwConfig[key] ?: "").replace("\\", "\\\\").replace("\"", "\\\"") + "\""

val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "de.bartuan.chatwrapped"
    compileSdk = 35

    defaultConfig {
        applicationId = "de.bartuan.chatwrapped"
        minSdk = 26
        targetSdk = 35
        versionCode = 3
        versionName = "1.2"
        buildConfigField("String", "CW_SERVER", cwConst("server"))
        buildConfigField("String", "CW_REQ_TOPIC", cwConst("req_topic"))
        buildConfigField("String", "CW_KEY_B64", cwConst("aes_key_b64"))
    }

    signingConfigs {
        create("release") {
            if (keystoreProps.isNotEmpty()) {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("release")
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
        buildConfig = true
    }
    // No dependency metadata blob in the APK (nothing phones home either way)
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            all {
                it.maxHeapSize = "2000m"
                it.systemProperty("roborazzi.test.record", "true")
                it.systemProperty("robolectric.pixelCopyRenderMode", "hardware")
                it.systemProperty("screenshotDir", rootProject.file("screenshots").absolutePath)
                (project.findProperty("cwE2eDir") as String?)?.let { d -> it.systemProperty("cw.e2e.dir", d) }
                (project.findProperty("cwE2ePhase") as String?)?.let { ph -> it.systemProperty("cw.e2e.phase", ph) }
                it.systemProperty("sampleChat", project.file("src/test/resources/sample_chat.txt").absolutePath)
            }
        }
    }
    packaging {
        resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}", "META-INF/versions/**")
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.material3:material3")

    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("androidx.test:core-ktx:1.6.1")
    testImplementation("androidx.test.ext:junit:1.2.1")
    testImplementation(composeBom)
    testImplementation("androidx.compose.ui:ui-test-junit4")
    testImplementation("io.github.takahirom.roborazzi:roborazzi:1.32.2")
    testImplementation("io.github.takahirom.roborazzi:roborazzi-compose:1.32.2")
}
