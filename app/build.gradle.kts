import java.net.URI
import java.security.MessageDigest

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "app.clipit"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.clipit"
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"
    }

    // Python, QuickJS and ffmpeg ship as native libraries, so one APK per
    // CPU type keeps each download to a third of the universal size.
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86_64")
            isUniversalApk = false
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Signed with the debug key so the APK installs straight away.
            // Swap in your own keystore before distributing it.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }

    packaging {
        // The bundled interpreter and ffmpeg are executed from the native
        // library directory, which only exists when libraries are extracted.
        jniLibs { useLegacyPackaging = true }
        resources { excludes += setOf("META-INF/{AL2.0,LGPL2.1}", "META-INF/*.kotlin_module") }
    }
}

/**
 * gallery-dl and its pure-Python dependencies are imported directly from their
 * wheel files at runtime, so they are fetched from PyPI (hash-pinned) at build
 * time and shipped as assets. yt-dlp comes with the youtubedl-android library.
 */
abstract class FetchWheels : DefaultTask() {
    @get:Input
    abstract val wheels: MapProperty<String, String>

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun fetch() {
        val dir = outputDir.get().asFile.resolve("pydeps")
        dir.deleteRecursively()
        dir.mkdirs()
        wheels.get().forEach { (url, sha256) ->
            val target = dir.resolve(url.substringAfterLast('/'))
            val bytes = URI(url).toURL().openStream().use { it.readBytes() }
            val actual = MessageDigest.getInstance("SHA-256").digest(bytes)
                .joinToString("") { "%02x".format(it) }
            check(actual == sha256) { "Checksum mismatch for ${target.name}: $actual" }
            target.writeBytes(bytes)
        }
    }
}

val pypi = "https://files.pythonhosted.org/packages"
val fetchWheels = tasks.register<FetchWheels>("fetchWheels") {
    wheels.set(
        mapOf(
            "$pypi/5d/c3/87c38fa0d392455a14b3f32b8a3acf0887ced4689a7b5fc6e0d9f761213f/gallery_dl-1.32.19-py3-none-any.whl" to
                "f6b1eb5e9a7409a988b754335d99dbb1873e130d133ed5d164d32e5e645d1c50",
            "$pypi/a0/f4/c67b0b3f1b9245e8d266f0f112c500d50e5b4e83cb6f3b71b6528104182a/requests-2.34.2-py3-none-any.whl" to
                "2a0d60c172f83ac6ab31e4554906c0f3b3588d37b5cb939b1c061f4907e278e0",
            "$pypi/92/9d/c4e665119135114480843e7ab388fa94d8480650450e6f8e26b70d323a4c/urllib3-2.8.0-py3-none-any.whl" to
                "0cf3cae568d36aa9576b28dfb35f11328f1cb974ca7647d9475ebb86c75ac6e3",
            "$pypi/32/d1/a3652fe2c19134d0d15922177821d863cbd7e842d53a21bbe7e8e3a1b5ac/certifi-2026.10.23-py3-none-any.whl" to
                "e9b54de76e530bb6cf0ccd036787103acb58fd42ba930ea8bf948ddbaa2f61d1",
            "$pypi/58/a2/bb081bab032533a855d44de1d56f8e8426114ff1ba5d1f07a438a0a654f8/idna-3.20-py3-none-any.whl" to
                "ab7ae7122974553370f0bdb919e1a960b2cd1bc1ef0276416d896db81c14582c",
            "$pypi/fc/ad/d07d7862a62ffa6d79d68074d14823243dd235a77c45262acbf6adeb28bf/charset_normalizer-3.5.2-py3-none-any.whl" to
                "b6b751274acb69d77b3323d6b7dbaa3c7fdfc1eb829b7eb61d262f32e1af9685",
        )
    )
}

androidComponents {
    onVariants { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(fetchWheels, FetchWheels::outputDir)
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation(platform("androidx.compose:compose-bom:2025.04.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.1")

    // Bundles Python, yt-dlp, QuickJS (needed for YouTube) and ffmpeg.
    implementation("io.github.junkfood02.youtubedl-android:library:0.18.1")
    implementation("io.github.junkfood02.youtubedl-android:ffmpeg:0.18.1")
}
