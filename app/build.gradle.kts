import com.android.build.api.dsl.ApplicationExtension
import java.util.Properties

plugins {
    id("eclipse")
    id("com.google.devtools.ksp") version "2.3.6"
    id("com.google.dagger.hilt.android") version "2.59.2"
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)

    kotlin("plugin.serialization") version "2.3.0"
}

val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

ksp {
    arg("dagger.fastInit", "enabled")
    arg("dagger.formatGeneratedSource", "disabled")
    arg("ksp.incremental", "true")
    arg("room.schemaLocation", "$projectDir/schemas")
}

val majorVersion = 1
val minorVersion = 10
val patchVersion = 0

extensions.configure<ApplicationExtension>("android") {
    compileSdk = 37
    namespace = "cc.tomko.outify"

    buildFeatures {
        buildConfig = true
    }

    defaultConfig {
        applicationId = "cc.tomko.outify"
        minSdk = 26
        targetSdk = 36
        versionCode = majorVersion * 10_000 + minorVersion * 100 + patchVersion
        versionName = "$majorVersion.$minorVersion.$patchVersion"

        buildConfigField("String", "SPOTIFY_CLIENT_ID", "\"${keystoreProps.getProperty("spotify.playback.clientId", "")}\"")
        buildConfigField("String", "SPOTIFY_CLIENT_SECRET", "\"${keystoreProps.getProperty("spotify.playback.clientSecret", "")}\"")
    }

    signingConfigs {
        create("ciRelease") {
            val keystoreFile = System.getenv("ANDROID_KEYSTORE_FILE")
            val keystorePassword = System.getenv("ANDROID_KEYSTORE_PASSWORD")
            val keyAlias = System.getenv("ANDROID_KEY_ALIAS")
            val keyPassword = System.getenv("ANDROID_KEY_PASSWORD")

            if (!keystoreFile.isNullOrBlank() &&
                !keystorePassword.isNullOrBlank() &&
                !keyAlias.isNullOrBlank() &&
                !keyPassword.isNullOrBlank()
            ) {
                storeFile = file(keystoreFile)
                storePassword = keystorePassword
                this.keyAlias = keyAlias
                this.keyPassword = keyPassword
            } else {
                // Fallback to debug
            }
        }
    }

    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true

            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (
                !System.getenv("ANDROID_KEYSTORE_FILE").isNullOrBlank() &&
                !System.getenv("ANDROID_KEYSTORE_PASSWORD").isNullOrBlank() &&
                !System.getenv("ANDROID_KEY_ALIAS").isNullOrBlank() &&
                !System.getenv("ANDROID_KEY_PASSWORD").isNullOrBlank()
            ) {
                signingConfig = signingConfigs.getByName("ciRelease")
            } else {
                signingConfig = signingConfigs.getByName("debug")
            }
        }

        debug {
            isMinifyEnabled = false
            isShrinkResources = false
            isDebuggable = true
        }
    }

    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a")
            isUniversalApk = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    packaging {
        resources {
            excludes += setOf(
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE",
                "META-INF/LICENSE.txt",
                "META-INF/NOTICE",
                "META-INF/NOTICE.txt"
            )
        }
    }
}

kotlin {
    jvmToolchain(17)
    compilerOptions {
        freeCompilerArgs.addAll("-Xjvm-default=all")
    }
}

dependencies {
    constraints {
        implementation("org.jetbrains:annotations:23.0.0") {
            because("Unify annotations artifact to avoid duplicate classes")
        }
    }

//    // Rustls
//    implementation(libs.rustls.platform.verifier)

    implementation(libs.appcompat)
    implementation(libs.activity)
    implementation(libs.constraintlayout)
    implementation(libs.tink.android)
    implementation(libs.lifecycle.runtime.ktx)
    implementation(libs.activity.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.ui)
    implementation(libs.ui.graphics)
    implementation(libs.ui.tooling.preview)
    implementation(libs.animation.core)
    implementation(libs.navigation3.runtime)
    implementation(libs.adaptive.navigation3)

    // Glance widget
    implementation(libs.androidx.glance)
    implementation(libs.androidx.glance.appwidget)

    // Preferences
    implementation(libs.androidx.datastore.preferences)

    // Hilt
    implementation(libs.hilt.android)
    implementation(libs.androidx.lifecycle.service)
    ksp(libs.hilt.compiler)
    ksp(libs.hilt.android.compiler)
    implementation(libs.androidx.hilt.navigation.compose)

    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)

    // Coil images
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)

    implementation(libs.androidx.palette)
    implementation(libs.google.material)

    // Native metadata serialization
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)

    // Reorderable lists
    implementation(libs.reorderable)

    // Media3
    implementation(libs.androidx.media3.ui)
    implementation(libs.androidx.media3.common)
    implementation(libs.androidx.media3.session)

    // HTTP server for oauth
    implementation(libs.nanohttpd)

    implementation(libs.kotlinx.coroutines.guava)

    implementation(platform(libs.compose.bom))
    implementation(libs.material)
    implementation(libs.material3.window.size.class1)
    implementation(libs.navigation.compose)

//    implementation(libs.room.compiler){
//        exclude(group = "com.intellij", module = "annotations")
//    }
    implementation(libs.room.runtime){
        exclude(group = "com.intellij", module = "annotations")
    }
    implementation(libs.room.ktx){
        exclude(group = "com.intellij", module = "annotations")
    }
    ksp(libs.room.compiler)

    testImplementation(libs.junit)
    androidTestImplementation(libs.ext.junit)
    androidTestImplementation(libs.espresso.core)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.ui.test.junit4)
    debugImplementation(libs.ui.tooling)
    debugImplementation(libs.ui.test.manifest)
}
