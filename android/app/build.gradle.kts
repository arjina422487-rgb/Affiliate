import java.util.Properties

// Single source of truth for every Android API and WebSocket connection.
val affiliateBaseUrl = providers.gradleProperty("affiliateBaseUrl")
    .orElse(providers.environmentVariable("AFFILIATE_BASE_URL"))
    .getOrElse("").trim().trimEnd('/')
require(affiliateBaseUrl.isEmpty() || (affiliateBaseUrl.startsWith("https://") && !affiliateBaseUrl.contains('"') && !affiliateBaseUrl.contains('\\') && !affiliateBaseUrl.contains('\n'))) {
    "affiliateBaseUrl must be an HTTPS URL"
}

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("org.jetbrains.kotlin.kapt")
}

val externalSigningFile = file(System.getProperty("user.home") + "/.privatchat/signing.properties")
val externalSigning = Properties().apply {
    if (externalSigningFile.isFile) externalSigningFile.inputStream().use { load(it) }
}

android {
    namespace = "com.ownerlink.agent"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.ownerlink.agent"
        minSdk = 29
        targetSdk = 34
        versionCode = 27
        versionName = "3.3.3"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        manifestPlaceholders["usesCleartext"] = "false"
        buildConfigField("String", "BASE_URL", "\"$affiliateBaseUrl\"")
    }

    signingConfigs {
        if (externalSigningFile.isFile) create("privatChatRelease") {
            storeFile = file(externalSigning.getProperty("storeFile"))
            storePassword = externalSigning.getProperty("storePassword")
            keyAlias = externalSigning.getProperty("keyAlias")
            keyPassword = externalSigning.getProperty("keyPassword")
        }
    }

    buildTypes {
        debug {
            buildConfigField("boolean", "ALLOW_INSECURE_HTTP", "true")
            buildConfigField("boolean", "FEATURE_CAMERA_MIC", "false")
            manifestPlaceholders["usesCleartext"] = "true"
        }
        release {
            // WebRTC's native JNI bootstrap crashes on Android 12 when this
            // dependency is rewritten by R8. Keep the signed release unminified
            // until the WebRTC artifact can be upgraded and validated separately.
            isMinifyEnabled = false
            isShrinkResources = false
            buildConfigField("boolean", "ALLOW_INSECURE_HTTP", "false")
            buildConfigField("boolean", "FEATURE_CAMERA_MIC", "false")
            if (externalSigningFile.isFile) signingConfig = signingConfigs.getByName("privatChatRelease")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
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

    // Compose's bundled lint detector cannot read the Kotlin 2.1 metadata
    // published by current Room. Disable only the crashing detector until the
    // Android Gradle lint runtime is updated; the source still compiles/tests.
    lint {
        disable += "StateFlowValueCalledInComposition"
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

kapt {
    arguments { arg("room.schemaLocation", "$projectDir/schemas") }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.06.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("androidx.activity:activity-compose:1.9.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.4")
    implementation("androidx.lifecycle:lifecycle-service:2.8.4")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.security:security-crypto:1.0.0")
    implementation("androidx.work:work-runtime-ktx:2.9.1")
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("com.google.zxing:core:3.5.3")
    implementation("com.google.android.gms:play-services-code-scanner:16.1.0")
    implementation("androidx.room:room-runtime:2.8.5")
    implementation("androidx.room:room-ktx:2.8.5")
    kapt("androidx.room:room-compiler:2.8.5")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.room:room-testing:2.8.5")
    androidTestImplementation(composeBom)
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}

android.applicationVariants.all {
    if (buildType.name == "release") {
        outputs.all {
            (this as com.android.build.gradle.internal.api.BaseVariantOutputImpl).outputFileName = "Affiliate.apk"
        }
    }
}
