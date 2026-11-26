package app.tgplayer

import android.util.Base64

/**
 * Unmasks the API keys that build.gradle.kts XOR-masks into BuildConfig, so they are not readable strings in
 * the APK. Callers read [tmdb] / [openSubtitles] / [telegramApiHash] instead of BuildConfig directly; the
 * `available` flags still test the masked BuildConfig value (empty stays empty), so they are unchanged.
 *
 * tails: obfuscation, not encryption. The mask below ships in the APK, so this only raises the bar against a
 * casual `strings` dump or key scraper — a determined reverse-engineer can still recover the keys, and the app
 * sends the plaintext key on the wire where traffic capture sees it. The real fix is the server proxy
 * (route TMDb/OpenSubtitles through tgplayer-offers.vercel.app with Play Integrity). Name-level scrambling of
 * this object comes from R8/minification, which the release build does not yet enable.
 */
internal object Secrets {
    // MUST stay identical to keyMask in app/build.gradle.kts.
    private val m = intArrayOf(0x5A, 0x1C, 0x77, 0x29, 0x6B, 0x3E, 0x42, 0x11)

    private fun reveal(s: String): String {
        if (s.isEmpty()) return ""
        val b = Base64.decode(s, Base64.NO_WRAP)
        for (i in b.indices) b[i] = (b[i].toInt() xor m[i % m.size]).toByte()
        return String(b, Charsets.UTF_8)
    }

    init {
        // Round-trips a known plaintext: throws at startup if the mask bytes or Base64 mode drift from the build.
        check(reveal(BuildConfig.SECRET_CANARY) == "TGPlayer") { "Secrets mask out of sync with build.gradle.kts" }
    }

    val telegramApiHash get() = reveal(BuildConfig.TELEGRAM_API_HASH)
    val tmdb get() = reveal(BuildConfig.TMDB_API_KEY)
    val openSubtitles get() = reveal(BuildConfig.OPENSUBTITLES_API_KEY)
}
