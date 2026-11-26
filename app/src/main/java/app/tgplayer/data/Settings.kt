package app.tgplayer.data

import android.content.Context
import android.content.SharedPreferences

/** User preferences (small key-value settings). Shared by the app and the playback service. */
object Settings {
    private lateinit var prefs: SharedPreferences

    fun init(context: Context) {
        if (!::prefs.isInitialized) prefs = context.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)
    }

    /** Streaming cache limit (Telegram + network links), in GB. */
    var cacheLimitGb by int("cache_limit_gb", 5)
    /** Download a network file to disk ahead of playback, so it keeps buffering while paused. */
    var readAhead by bool("read_ahead", true)
    /** Keep a video's sound playing after leaving the player. */
    var backgroundVideo by bool("background_video", false)
    /** Shrink to picture-in-picture when leaving a playing video. */
    var autoPip by bool("auto_pip", true)
    /** Play the next file in the folder when one ends. */
    var autoPlayNext by bool("auto_play_next", true)
    /** Resume local files and links where they were left. */
    var resume by bool("resume", true)
    var showHiddenFiles by bool("show_hidden", false)
    /** Music tab: leave out chat-app voice notes (WhatsApp, Telegram…) and clips under 30 s. */
    var hideChatAudio by bool("hide_chat_audio", true)
    /** Use FFmpeg before the phone's own audio decoders (applies the next time playback starts fresh). */
    var preferFfmpeg by bool("prefer_ffmpeg", false)
    /** Subtitle text size as a fraction of video height (SubtitleView fractional size). */
    var subtitleScale by float("subtitle_scale", 0.0533f)
    /** Look up a subtitle on OpenSubtitles.com when a video has none built in or beside it. */
    var findSubtitlesOnline by bool("find_subtitles_online", true)
    /** Equalizer ([app.tgplayer.player.AudioFx]): on/off, band gains in dB as "g1,g2,…", preamp in dB. */
    var eqEnabled by bool("eq_enabled", false)
    var eqGains: String
        get() = prefs.getString("eq_gains", "").orEmpty()
        set(value) = prefs.edit().putString("eq_gains", value).apply()
    var eqPreamp by float("eq_preamp", 0f)
    /** URLs typed into "Network stream", newest first. */
    var recentUrls: List<String>
        get() = prefs.getString("recent_urls", "").orEmpty().split('\n').filter { it.isNotBlank() }
        set(value) = prefs.edit().putString("recent_urls", value.take(20).joinToString("\n")).apply()
    /** Audio/subtitle language last picked in the player (ISO code, "" = the file's default); new files start with it. */
    var preferredAudioLanguage: String
        get() = prefs.getString("pref_audio_lang", "").orEmpty()
        set(value) = prefs.edit().putString("pref_audio_lang", value).apply()
    var preferredSubtitleLanguage: String
        get() = prefs.getString("pref_text_lang", "").orEmpty()
        set(value) = prefs.edit().putString("pref_text_lang", value).apply()
    /** Network drives added in Browse, as a JSON array ([app.tgplayer.local.NetworkDrives] reads it). */
    var networkDrivesJson: String
        get() = prefs.getString("network_drives", "[]").orEmpty()
        set(value) = prefs.edit().putString("network_drives", value).apply()
    /** The signed-in Jellyfin server as JSON ([Jellyfin.server]), "" when none. */
    var jellyfinJson: String
        get() = prefs.getString("jellyfin", "").orEmpty()
        set(value) = prefs.edit().putString("jellyfin", value).apply()
    /** This install's id towards Jellyfin, so the server lists it as one device across sign-ins. */
    var jellyfinDeviceId: String
        get() = prefs.getString("jellyfin_device_id", "").orEmpty()
        set(value) = prefs.edit().putString("jellyfin_device_id", value).apply()
    /** Folders the Library scans: file:// and smb:// URIs. */
    var libraryFolders: List<String>
        get() = prefs.getString("library_folders", "").orEmpty().split('\n').filter { it.isNotBlank() }
        set(value) = prefs.edit().putString("library_folders", value.distinct().joinToString("\n")).apply()

    private fun int(key: String, default: Int) = object : kotlin.properties.ReadWriteProperty<Any?, Int> {
        override fun getValue(thisRef: Any?, property: kotlin.reflect.KProperty<*>) = prefs.getInt(key, default)
        override fun setValue(thisRef: Any?, property: kotlin.reflect.KProperty<*>, value: Int) = prefs.edit().putInt(key, value).apply()
    }

    private fun bool(key: String, default: Boolean) = object : kotlin.properties.ReadWriteProperty<Any?, Boolean> {
        override fun getValue(thisRef: Any?, property: kotlin.reflect.KProperty<*>) = prefs.getBoolean(key, default)
        override fun setValue(thisRef: Any?, property: kotlin.reflect.KProperty<*>, value: Boolean) = prefs.edit().putBoolean(key, value).apply()
    }

    private fun float(key: String, default: Float) = object : kotlin.properties.ReadWriteProperty<Any?, Float> {
        override fun getValue(thisRef: Any?, property: kotlin.reflect.KProperty<*>) = prefs.getFloat(key, default)
        override fun setValue(thisRef: Any?, property: kotlin.reflect.KProperty<*>, value: Float) = prefs.edit().putFloat(key, value).apply()
    }
}
