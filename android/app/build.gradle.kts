plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

// Handset is installed by hand and never ships to Play, so it can use an accessibility service this way.

fun secret(name: String): String? = System.getenv(name) ?: (findProperty(name) as String?)

/**
 * The release key. A build machine can pass it in the environment. A machine that keeps a copy in ~/.config/handset
 * signs its local builds with it, so they install over each other. Without either, a release build uses the debug key.
 */
val localKey = File(System.getProperty("user.home"), ".config/handset/release.jks")
val keystore: String? = secret("HANDSET_KEYSTORE") ?: localKey.takeIf { it.exists() }?.path
val keystorePassword: String? = secret("HANDSET_KEYSTORE_PASSWORD")
    ?: File(localKey.parentFile, "release.password").takeIf { it.exists() }?.readText()?.trim()

/**
 * The minutes since 2026-01-01, for every build, so whichever build is newer installs over the other. It runs when a
 * task reads the version code, so a reused configuration cache gets a new value.
 */
abstract class MinutesVersionCode : ValueSource<Int, ValueSourceParameters.None> {
    override fun obtain(): Int = ((System.currentTimeMillis() / 60_000L) - 29_453_760L).toInt()
}

android {
    namespace = "ai.tushar.handset"
    compileSdk = 37

    defaultConfig {
        applicationId = "ai.tushar.handset"
        // Accessibility screenshots need API 30
        minSdk = 30
        targetSdk = 37
        versionName = "0.1.0"
    }

    signingConfigs {
        // Every release build must carry the same key, or Android refuses the update
        create("release") {
            if (keystore != null) {
                storeFile = file(keystore)
                storePassword = keystorePassword
                keyAlias = "handset"
                keyPassword = keystorePassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName(if (keystore != null) "release" else "debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        // Robolectric gives the store's tests a Context
        unitTests.isIncludeAndroidResources = true
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.room.runtime)
    implementation(libs.sqlite.bundled)
    implementation(libs.androidx.work.runtime)
    ksp(libs.room.compiler)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.work.testing)
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    // The desktop build of the bundled SQLite, with FTS5, for the store's tests
    testImplementation(libs.sqlite.bundled.jvm)
}

androidComponents {
    onVariants { variant ->
        val versionCode = providers.of(MinutesVersionCode::class) {}
        variant.outputs.forEach { it.versionCode.set(versionCode) }
    }
}
