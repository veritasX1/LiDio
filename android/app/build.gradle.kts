import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("io.github.takahirom.roborazzi")
}

val keystoreProperties = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

val hasExtension = file("privat.gradle.kts").exists()

android {
    namespace = "io.github.veritasx1.lidio"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.veritasx1.lidio"
        minSdk = 29
        targetSdk = 35
        versionCode = 6
        versionName = "0.4b"
    }

    // "oeffentlich" is LiDio as published. A further variant exists only where its own build file is present (privat.gradle.kts).
    flavorDimensions += "variante"
    productFlavors {
        create("oeffentlich") {
            dimension = "variante"
        }
        if (hasExtension) create("privat") {
            dimension = "variante"
            applicationIdSuffix = ".privat"
            versionNameSuffix = "-privat"
            ndk { abiFilters += listOf("arm64-v8a") }
            proguardFile("proguard-privat.pro")
        }
    }

    signingConfigs {
        create("release") {
            if (keystoreProperties.isNotEmpty()) {
                storeFile = rootProject.file(keystoreProperties["storeFile"] as String)
                storePassword = keystoreProperties["storePassword"] as String
                keyAlias = keystoreProperties["keyAlias"] as String
                keyPassword = keystoreProperties["keyPassword"] as String
            }
        }
    }

    buildTypes {
        release {
            signingConfig = if (keystoreProperties.isNotEmpty()) signingConfigs.getByName("release") else signingConfigs.getByName("debug")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (project.hasProperty("probe")) applicationIdSuffix = ".probe"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.all {
            it.systemProperty("robolectric.pixelCopyRenderMode", "hardware")
            // Tests check the German texts (the source language) – regardless of the computer's locale.
            it.systemProperty("lidio.language", (project.findProperty("lang") as String?) ?: "de")
            // Pictures: ./gradlew testDebugUnitTest -Pshots=/folder
            (project.findProperty("shots") as String?)?.let { folder -> it.systemProperty("lidio.shots", folder) }
        }
    }

    packaging {
        resources.excludes.add("/META-INF/{AL2.0,LGPL2.1}")
        // Native programs are unpacked from these .so files at runtime – they must stay as files.
        jniLibs.useLegacyPackaging = true
    }
}

// The translations: one catalogue for both apps, kept with the Ubuntu app.
val copyLocale by tasks.registering(Sync::class) {
    from(rootProject.file("../linux/lidio/locale")) { include("*.json") }
    into(layout.buildDirectory.dir("generated/locale/locale"))
}
android.sourceSets["main"].assets.srcDir(layout.buildDirectory.dir("generated/locale"))
tasks.named("preBuild") { dependsOn(copyLocale) }

dependencies {
    // AndroidX and Media3 only – no analytics, ads or tracking. The network goes to the user's own server.
    val composeBom = platform("androidx.compose:compose-bom:2024.09.03")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.foundation:foundation")
    // "Modern" look (card d894cc42): the frosted glass of iOS 26 – real background blur (Apache-2.0).
    implementation("dev.chrisbanes.haze:haze:1.5.4")
    val media3 = "1.9.0"
    implementation("androidx.media3:media3-exoplayer:$media3")
    implementation("androidx.media3:media3-session:$media3")
    implementation("androidx.media3:media3-datasource:$media3")
    implementation("androidx.media3:media3-database:$media3")
    // FFmpeg decoders (Jellyfin's build, GPL-3.0): AC3/E-AC3, DTS, TrueHD, ALAC, FLAC … when the phone lacks them.
    implementation("org.jellyfin.media3:media3-ffmpeg-decoder:1.9.0+1")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.13")
    testImplementation("io.github.takahirom.roborazzi:roborazzi:1.32.2")
    testImplementation("io.github.takahirom.roborazzi:roborazzi-compose:1.32.2")
    testImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}

if (hasExtension) apply(from = "privat.gradle.kts")
