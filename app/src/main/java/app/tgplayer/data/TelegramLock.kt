package app.tgplayer.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * Optional password in front of the Telegram section.
 *
 * Only a salted PBKDF2 hash is stored (never the password), in app-private storage that is excluded
 * from backups. Wrong guesses lock it for a growing time, which survives app restarts.
 * Forgetting the password means logging out of Telegram, which also removes the lock.
 */
object TelegramLock {
    const val MIN_LENGTH = 4
    private const val ITERATIONS = 150_000
    private lateinit var prefs: SharedPreferences

    fun init(context: Context) {
        if (!::prefs.isInitialized) prefs = context.applicationContext.getSharedPreferences("telegram_lock", Context.MODE_PRIVATE)
    }

    val isSet: Boolean get() = prefs.contains(KEY_HASH)

    /**
     * Unlocked for this visit to the Telegram section. Kept in memory only, so it survives rotation
     * but not leaving the section or the app process ending (no saved state can bypass the lock).
     */
    @Volatile var unlockedThisVisit = false

    sealed interface Result {
        data object Ok : Result
        data class Wrong(val attemptsBeforeWait: Int) : Result
        data class Wait(val seconds: Long) : Result
    }

    suspend fun set(password: String) = withContext(Dispatchers.Default) {
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        prefs.edit()
            .putString(KEY_SALT, salt.b64())
            .putString(KEY_HASH, hash(password, salt, ITERATIONS).b64())
            .putInt(KEY_ITERATIONS, ITERATIONS)
            .putInt(KEY_FAILURES, 0)
            .putLong(KEY_WAIT_UNTIL, 0)
            .apply()
        unlockedThisVisit = true // whoever just set it is already in
    }

    fun clear() {
        prefs.edit().clear().apply()
        unlockedThisVisit = false
    }

    /** Seconds left before another try is allowed (0 = may try now). */
    fun waitSeconds(): Long {
        val until = prefs.getLong(KEY_WAIT_UNTIL, 0)
        val now = System.currentTimeMillis()
        // A clock moved backwards shouldn't stretch the wait: cap it at the longest one we hand out.
        return ((until - now).coerceAtMost(MAX_WAIT_MS) + 999).coerceAtLeast(0) / 1000
    }

    suspend fun check(password: String): Result {
        waitSeconds().takeIf { it > 0 }?.let { return Result.Wait(it) }
        val salt = prefs.getString(KEY_SALT, null)?.unb64() ?: return Result.Ok
        val expected = prefs.getString(KEY_HASH, null)?.unb64() ?: return Result.Ok
        val iterations = prefs.getInt(KEY_ITERATIONS, ITERATIONS)
        val actual = withContext(Dispatchers.Default) { hash(password, salt, iterations) }
        if (MessageDigest.isEqual(actual, expected)) {
            prefs.edit().putInt(KEY_FAILURES, 0).putLong(KEY_WAIT_UNTIL, 0).apply()
            return Result.Ok
        }
        val failures = prefs.getInt(KEY_FAILURES, 0) + 1
        val waitMs = waitAfter(failures)
        prefs.edit().putInt(KEY_FAILURES, failures).putLong(KEY_WAIT_UNTIL, if (waitMs > 0) System.currentTimeMillis() + waitMs else 0).apply()
        return if (waitMs > 0) Result.Wait(waitMs / 1000) else Result.Wrong(FREE_ATTEMPTS - failures)
    }

    private const val KEY_SALT = "salt"
    private const val KEY_HASH = "hash"
    private const val KEY_ITERATIONS = "iterations"
    private const val KEY_FAILURES = "failures"
    private const val KEY_WAIT_UNTIL = "wait_until"
    private const val MAX_WAIT_MS = 15 * 60_000L

    private fun ByteArray.b64() = Base64.encodeToString(this, Base64.NO_WRAP)
    private fun String.unb64() = Base64.decode(this, Base64.NO_WRAP)
}

/** PBKDF2-HMAC-SHA256, 256-bit output. */
internal fun hash(password: String, salt: ByteArray, iterations: Int): ByteArray {
    val spec = PBEKeySpec(password.toCharArray(), salt, iterations, 256)
    try {
        return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
    } finally {
        spec.clearPassword()
    }
}

private const val FREE_ATTEMPTS = 5

/** After [FREE_ATTEMPTS] free tries: 30 s, then doubling per wrong try, capped at 15 minutes. */
internal fun waitAfter(failures: Int): Long {
    val over = failures - FREE_ATTEMPTS
    return if (over <= 0) 0 else minOf(30_000L shl minOf(over - 1, 10), 15 * 60_000L)
}
