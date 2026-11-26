import java.util.Base64
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// Telegram API credentials live in the git-ignored local.properties (telegram.apiId / telegram.apiHash).
val localProps = Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }?.reader()?.use { load(it) }
}
fun localProp(key: String): String =
    localProps.getProperty(key)?.trim()?.takeIf { it.isNotEmpty() }
        ?: error("Missing $key in local.properties. Get it from https://my.telegram.org")
val telegramApiId = localProp("telegram.apiId").also { require(it.all(Char::isDigit)) { "telegram.apiId must be a number" } }
val telegramApiHash = localProp("telegram.apiHash")
// Optional: an OpenSubtitles.com API key (https://www.opensubtitles.com/consumers) turns on online subtitle search.
val openSubtitlesApiKey = localProps.getProperty("opensubtitles.apiKey")?.trim().orEmpty()
// Optional: a TMDb API key (https://www.themoviedb.org/settings/api), the source Jellyfin uses, adds posters and details to Library.
val tmdbApiKey = localProps.getProperty("tmdb.apiKey")?.trim().orEmpty()

// XOR-masks a secret so it ships as base64 noise in BuildConfig, not a readable string in the APK. Reversed by
// app/src/main/java/app/tgplayer/Secrets.kt, which MUST keep the same bytes below and Base64.NO_WRAP.
// tails: obfuscation only — the mask ships in the APK too, so this slows a casual `strings`/scraper, it does not
// stop a determined reverse-engineer. Real fix: the server proxy (tgplayer-offers.vercel.app). An empty key stays empty.
val keyMask = intArrayOf(0x5A, 0x1C, 0x77, 0x29, 0x6B, 0x3E, 0x42, 0x11)
fun maskSecret(value: String): String {
    if (value.isEmpty()) return ""
    val bytes = value.toByteArray(Charsets.UTF_8)
    for (i in bytes.indices) bytes[i] = (bytes[i].toInt() xor keyMask[i % keyMask.size]).toByte()
    return Base64.getEncoder().encodeToString(bytes)
}

android {
    namespace = "app.tgplayer"
    // Compose 1.13 (BOM 2026.11.01) needs compileSdk 37.1+
    compileSdk {
        version = release(37) { minorApiLevel = 2 }
    }
    buildToolsVersion = "37.0.0"

    defaultConfig {
        applicationId = "app.tgplayer"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "0.1"

        buildConfigField("int", "TELEGRAM_API_ID", telegramApiId)
        buildConfigField("String", "TELEGRAM_API_HASH", "\"${maskSecret(telegramApiHash)}\"")
        buildConfigField("String", "OPENSUBTITLES_API_KEY", "\"${maskSecret(openSubtitlesApiKey)}\"")
        buildConfigField("String", "TMDB_API_KEY", "\"${maskSecret(tmdbApiKey)}\"")
        // Known plaintext, masked the same way: Secrets' init unmasks it and fails fast if the two sides drift.
        buildConfigField("String", "SECRET_CANARY", "\"${maskSecret("TGPlayer")}\"")
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    lint {
        // False positive: the Cast libraries declare fragment 1.0/1.1 but it resolves to 1.3.6, and every activity using
        // registerForActivityResult is a plain ComponentActivity (no FragmentActivity), so the Fragment bug can't happen.
        disable += "InvalidFragmentVersionForActivityResult"
    }

    buildTypes {
        // tails: release is signed with the debug key (the owner's choice), so it installs over the debug builds already
        // on phones and keeps their data. Before any Play Store upload, switch to a real release key (one reinstall).
        release {
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    // The BouncyCastle jars (via smbj/sshj) each carry the same licence files.
    packaging {
        resources.excludes += setOf("META-INF/LICENSE.md", "META-INF/NOTICE.md", "META-INF/versions/9/OSGI-INF/MANIFEST.MF")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

// smbj (bcprov 1.85.2) and sshj (bcpkix/bcutil 1.84) pull different BouncyCastle releases, and classes moved
// between those jars in 1.85: keep every BouncyCastle module on one release.
configurations.configureEach {
    resolutionStrategy.eachDependency { if (requested.group == "org.bouncycastle") useVersion("1.86") }
}

dependencies {
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.material3)
    implementation(libs.compose.icons.extended)
    implementation(libs.activity.compose)
    implementation(libs.androidx.annotation)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.ui)
    implementation(libs.media3.session)
    implementation(libs.media3.hls)
    implementation(libs.media3.dash)
    implementation(libs.media3.cast)
    implementation(libs.smbj)
    implementation(libs.commons.net)
    implementation(libs.sshj)
    testImplementation(libs.junit)
}
