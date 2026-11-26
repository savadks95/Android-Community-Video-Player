package app.tgplayer.local

import android.net.Uri
import app.tgplayer.data.MediaKind
import app.tgplayer.data.Settings
import app.tgplayer.data.playableKind
import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.msfscc.FileAttributes
import com.hierynomus.mserref.NtStatus
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.mssmb2.SMBApiException
import com.hierynomus.protocol.commons.EnumWithValue.EnumUtils
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.SmbConfig
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.common.SMBRuntimeException
import com.hierynomus.smbj.share.DiskShare
import com.hierynomus.smbj.share.File as SmbFile
import kotlinx.coroutines.Dispatchers
import org.apache.commons.net.ftp.FTP
import net.schmizz.sshj.DefaultConfig
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.common.SecurityUtils
import net.schmizz.sshj.sftp.RemoteFile
import net.schmizz.sshj.sftp.SFTPClient
import net.schmizz.sshj.transport.verification.HostKeyVerifier
import net.schmizz.sshj.userauth.UserAuthException
import org.apache.commons.net.ftp.FTPClient
import org.apache.commons.net.ftp.FTPReply
import org.apache.commons.net.ftp.FTPSClient
import java.security.Provider
import java.security.PublicKey
import java.security.Security
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.EnumSet
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * A network drive added in Browse: an SMB share (Windows shared folder, NAS), an FTP/FTPS server or
 * an SFTP (SSH) server. [host] may carry a port ("nas:4450"). For SMB [share] is the shared folder;
 * for the others it's the starting folder ("" = top). The password stays in app-private prefs (backups
 * are off) and never goes into a URI or the history. [hostKey] is the SFTP server's key fingerprint,
 * remembered when the drive was added and checked on every connection.
 */
data class NetworkDrive(
    val host: String,
    val share: String,
    val user: String = "",
    val password: String = "",
    val domain: String = "",
    val protocol: String = SMB,
    val hostKey: String = "",
) {
    val title get() = if (share.isEmpty()) host else "${share.substringAfterLast('/')} on $host"
    /** `smb://host/share` or `ftp://host/folder`; files below it are `.../dir/file.mkv`. */
    val root: Uri get() = Uri.Builder().scheme(protocol).encodedAuthority(host).apply { share.split('/').filter { it.isNotEmpty() }.forEach { appendPath(it) } }.build()
    fun owns(uri: Uri) = uri.scheme == protocol && host.equals(uri.encodedAuthority, true) &&
        (protocol != SMB || share.equals(uri.pathSegments.firstOrNull(), true))

    companion object {
        const val SMB = "smb"
        const val FTP = "ftp"
        const val FTPS = "ftps"
        const val SFTP = "sftp"
        val PROTOCOLS = setOf(SMB, FTP, FTPS, SFTP)
    }
}

/** A folder or playable file on a network drive. */
data class NetEntry(val name: String, val uri: Uri, val isDir: Boolean, val sizeBytes: Long, val kind: MediaKind?)

/** Network drives over SMB 2/3 and FTP: the saved list, folder listing, and opening files for the player. */
object NetworkDrives {
    private val client by lazy { SMBClient(SmbConfig.builder().withTimeout(20, TimeUnit.SECONDS).withSoTimeout(30, TimeUnit.SECONDS).build()) }
    private val shares = ConcurrentHashMap<NetworkDrive, DiskShare>()

    fun all(): List<NetworkDrive> {
        val array = runCatching { JSONArray(Settings.networkDrivesJson) }.getOrDefault(JSONArray())
        return (0 until array.length()).map { i ->
            array.getJSONObject(i).run {
                NetworkDrive(getString("host"), getString("share"), optString("user"), optString("password"), optString("domain"), optString("protocol", NetworkDrive.SMB), optString("hostKey"))
            }
        }
    }

    private fun save(drives: List<NetworkDrive>) {
        Settings.networkDrivesJson = JSONArray(drives.map { d ->
            JSONObject().put("host", d.host).put("share", d.share).put("user", d.user).put("password", d.password).put("domain", d.domain).put("protocol", d.protocol).put("hostKey", d.hostKey)
        }).toString()
    }

    /**
     * Connects first (so a typo or wrong password shows up now), then saves the drive. An FTP drive
     * with no folder starts where the server puts the user after login. Returns the saved drive.
     */
    suspend fun add(drive: NetworkDrive): NetworkDrive = withContext(Dispatchers.IO) {
        val saved = if (drive.protocol == NetworkDrive.FTP || drive.protocol == NetworkDrive.FTPS) {
            val c = ftpConnect(drive)
            try {
                val start = drive.share.ifEmpty { c.printWorkingDirectory().orEmpty() }.trim('/')
                ftpChildren(c, drive.copy(share = start).root)
                drive.copy(share = start)
            } finally {
                ftpClose(c)
            }
        } else if (drive.protocol == NetworkDrive.SFTP) {
            if (drive.user.isEmpty()) throw IOException("SFTP needs a user name")
            var fingerprint = ""
            // First contact: trust the key the server shows, and keep its fingerprint for next time.
            val session = sftpConnect(drive) { fingerprint = it; true }
            try {
                val start = drive.share.ifEmpty { session.second.canonicalize(".") }.trim('/')
                sftpChildren(session.second, drive.copy(share = start).root)
                drive.copy(share = start, hostKey = fingerprint)
            } finally {
                runCatching { session.first.disconnect() }
            }
        } else {
            connect(drive)
            drive
        }
        save(all().filterNot { it.protocol == saved.protocol && it.host.equals(saved.host, true) && it.share.equals(saved.share, true) } + saved)
        saved
    }

    fun remove(drive: NetworkDrive) {
        shares.remove(drive)?.let { runCatching { it.close() } }
        sftpSessions.remove(drive)?.let { runCatching { it.first.disconnect() } }
        save(all() - drive)
    }

    fun forUri(uri: Uri): NetworkDrive? = all().firstOrNull { it.owns(uri) }

    /** Folders first, then playable files, both by name. Other files are hidden. */
    suspend fun list(dir: Uri, showHidden: Boolean): List<NetEntry> = withContext(Dispatchers.IO) {
        val entries = browse(dir) { children -> children(dir) }.filter { showHidden || !it.second }.map { it.first }
        entries.filter { it.isDir }.sortedBy { it.name.lowercase() } + entries.filter { !it.isDir && it.kind != null }.sortedBy { it.name.lowercase() }
    }

    /** Subtitle files beside a video on a network drive (movie.mkv → movie.srt, movie.en.srt…). */
    suspend fun sidecarSubtitles(video: Uri): List<NetEntry> = withContext(Dispatchers.IO) {
        val parent = Uri.Builder().scheme(video.scheme).encodedAuthority(video.encodedAuthority)
            .apply { video.pathSegments.dropLast(1).forEach { appendPath(it) } }.build()
        val name = video.lastPathSegment ?: return@withContext emptyList()
        browse(parent) { children -> children(parent) }.map { it.first }.filter { !it.isDir && LocalMedia.isSidecar(name, it.name) }.sortedBy { it.name }
    }

    /** Every video under [dir], for the Library scan. Depth-limited in case of folder loops. */
    suspend fun videosUnder(dir: Uri): List<NetEntry> = withContext(Dispatchers.IO) {
        browse(dir) { children ->
            fun walk(uri: Uri, depth: Int): List<NetEntry> = children(uri).filterNot { it.second }.map { it.first }.flatMap { e ->
                when {
                    e.isDir -> if (depth < 8) walk(e.uri, depth + 1) else emptyList()
                    e.kind == MediaKind.VIDEO -> listOf(e)
                    else -> emptyList()
                }
            }
            walk(dir, 0)
        }
    }

    /** Runs [block] with one connection to [dir]'s drive and a lister of folders on it (entry, hidden). */
    private fun <T> browse(dir: Uri, block: ((Uri) -> List<Pair<NetEntry, Boolean>>) -> T): T =
        when (dir.scheme) {
            NetworkDrive.FTP, NetworkDrive.FTPS -> {
                val c = ftpConnect(dir)
                try {
                    block { ftpChildren(c, it) }
                } finally {
                    ftpClose(c)
                }
            }
            NetworkDrive.SFTP -> withSftp(dir) { sftp -> block { sftpChildren(sftp, it) } }
            else -> withShare(dir) { share -> block { smbChildren(share, it) } }
        }

    /** Opens a file for reading (the player's data source closes it). */
    fun open(uri: Uri): SmbFile = withShare(uri) { share ->
        share.openFile(pathOf(uri), EnumSet.of(AccessMask.GENERIC_READ), null, SMB2ShareAccess.ALL, SMB2CreateDisposition.FILE_OPEN, null)
    }

    private fun smbChildren(share: DiskShare, dir: Uri): List<Pair<NetEntry, Boolean>> =
        share.list(pathOf(dir)).filter { it.fileName != "." && it.fileName != ".." }.map { info ->
            val isDir = EnumUtils.isSet(info.fileAttributes, FileAttributes.FILE_ATTRIBUTE_DIRECTORY)
            val hidden = info.fileName.startsWith('.') || EnumUtils.isSet(info.fileAttributes, FileAttributes.FILE_ATTRIBUTE_HIDDEN)
            val uri = dir.buildUpon().appendPath(info.fileName).build()
            NetEntry(info.fileName, uri, isDir, info.endOfFile, if (isDir) null else playableKind(null, info.fileName)) to hidden
        }

    // --- FTP / FTPS ---
    // tails: one connection per listing and per player open. FTPS is explicit TLS ("AUTH TLS") and, like
    // most home-NAS clients, accepts self-signed certificates; servers that demand TLS session reuse on the
    // data connection (vsftpd require_ssl_reuse) won't list.

    /** A logged-in, passive, binary-mode FTP connection to [uri]'s drive; close it with [ftpClose]. */
    fun ftpConnect(uri: Uri): FTPClient = ftpConnect(forUri(uri) ?: throw IOException("This network drive was removed"))

    private fun ftpConnect(drive: NetworkDrive): FTPClient {
        val c = if (drive.protocol == NetworkDrive.FTPS) FTPSClient(false) else FTPClient()
        c.controlEncoding = "UTF-8"
        c.setAutodetectUTF8(true)
        c.connectTimeout = 20_000
        c.defaultTimeout = 30_000
        try {
            val port = drive.host.substringAfterLast(':', "").toIntOrNull()
            c.connect(if (port != null) drive.host.substringBeforeLast(':') else drive.host, port ?: FTP.DEFAULT_PORT)
            c.soTimeout = 30_000
            if (!FTPReply.isPositiveCompletion(c.replyCode)) throw IOException("${drive.host} refused the connection")
            // No user name: anonymous login, the FTP guest account.
            if (!c.login(drive.user.ifEmpty { "anonymous" }, if (drive.user.isEmpty()) "" else drive.password)) {
                throw IOException(if (drive.user.isEmpty()) "This server doesn't allow guests; enter a user name" else "Wrong user name or password")
            }
            if (c is FTPSClient) {
                c.execPBSZ(0)
                c.execPROT("P") // encrypt file transfers too, not just the login
            }
            c.enterLocalPassiveMode()
            c.setFileType(FTP.BINARY_FILE_TYPE)
            return c
        } catch (e: IOException) {
            ftpClose(c)
            throw e
        }
    }

    fun ftpClose(c: FTPClient) {
        runCatching { if (c.isConnected) c.disconnect() }
    }

    /** Absolute path on the server, e.g. `/Movies/Film.mkv`. */
    fun ftpPath(uri: Uri) = "/" + uri.pathSegments.joinToString("/")

    /** The file's size from the SIZE command (or a listing), or -1 when the server won't say. */
    fun ftpSize(c: FTPClient, path: String): Long {
        if (FTPReply.isPositiveCompletion(c.sendCommand("SIZE", path))) {
            c.replyString.trim().substringAfter(' ').toLongOrNull()?.let { return it }
        }
        return c.listFiles(path).singleOrNull()?.size ?: -1L
    }

    // --- SFTP ---

    /** One SSH session per drive, shared by listings and the player (SFTP carries many files at once). */
    private val sftpSessions = ConcurrentHashMap<NetworkDrive, Pair<SSHClient, SFTPClient>>()

    /** Android ships a cut-down "BC" provider; sshj needs the full one for modern keys (ed25519, curve25519). */
    private val bouncyCastle by lazy {
        Security.removeProvider("BC")
        // bcprov comes with smbj/sshj at runtime only, hence by name.
        Security.addProvider(Class.forName("org.bouncycastle.jce.provider.BouncyCastleProvider").getDeclaredConstructor().newInstance() as Provider)
    }

    /** Opens a file on an SFTP drive for reading (the player's data source closes it). */
    fun sftpOpen(uri: Uri): RemoteFile = withSftp(uri) { it.open(ftpPath(uri)) }

    private fun <T> withSftp(uri: Uri, block: (SFTPClient) -> T): T {
        val drive = forUri(uri) ?: throw IOException("This network drive was removed")
        val cached = sftpSessions[drive]
        val client = cached?.takeIf { it.first.isConnected }?.second ?: synchronized(sftpSessions) {
            sftpSessions[drive]?.takeIf { it.first.isConnected }?.second
                ?: sftpConnect(drive) { it == drive.hostKey }.also { sftpSessions[drive] = it }.second
        }
        return try {
            block(client)
        } catch (e: IOException) {
            // A kept session died (Wi-Fi switch, server restart): reconnect once.
            if (cached == null || sftpSessions[drive]?.first?.isConnected == true) throw e
            sftpSessions.remove(drive)
            block(sftpConnect(drive) { it == drive.hostKey }.also { sftpSessions[drive] = it }.second)
        }
    }

    /** Connects and logs in; [trust] decides on the server's key fingerprint. */
    private fun sftpConnect(drive: NetworkDrive, trust: (String) -> Boolean): Pair<SSHClient, SFTPClient> {
        bouncyCastle
        val ssh = SSHClient(DefaultConfig())
        var keyRejected = false
        ssh.addHostKeyVerifier(object : HostKeyVerifier {
            override fun verify(hostname: String, port: Int, key: PublicKey): Boolean =
                trust(SecurityUtils.getFingerprint(key)).also { keyRejected = !it }
            override fun findExistingAlgorithms(hostname: String, port: Int): List<String> = emptyList()
        })
        ssh.connectTimeout = 20_000
        ssh.timeout = 30_000
        try {
            val port = drive.host.substringAfterLast(':', "").toIntOrNull()
            ssh.connect(if (port != null) drive.host.substringBeforeLast(':') else drive.host, port ?: 22)
            ssh.authPassword(drive.user, drive.password)
            return ssh to ssh.newSFTPClient()
        } catch (e: Exception) {
            runCatching { ssh.disconnect() }
            throw IOException(
                when {
                    keyRejected -> "${drive.host}'s identity (SSH host key) changed. If you trust it, remove the drive and add it again."
                    e is UserAuthException -> "Wrong user name or password"
                    else -> e.message ?: "Couldn't connect"
                },
                e,
            )
        }
    }

    private fun sftpChildren(c: SFTPClient, dir: Uri): List<Pair<NetEntry, Boolean>> =
        c.ls(ftpPath(dir)).map { f ->
            val uri = dir.buildUpon().appendPath(f.name).build()
            NetEntry(f.name, uri, f.isDirectory, f.attributes.size, if (f.isDirectory) null else playableKind(null, f.name)) to f.name.startsWith('.')
        }

    private fun ftpChildren(c: FTPClient, dir: Uri): List<Pair<NetEntry, Boolean>> {
        val files = c.listFiles(ftpPath(dir))
        if (!FTPReply.isPositiveCompletion(c.replyCode)) throw IOException(c.replyString?.trim()?.ifEmpty { null } ?: "Couldn't open this folder")
        return files.filterNotNull().filter { it.name != "." && it.name != ".." }.map { f ->
            val uri = dir.buildUpon().appendPath(f.name).build()
            NetEntry(f.name, uri, f.isDirectory, f.size, if (f.isDirectory) null else playableKind(null, f.name)) to f.name.startsWith('.')
        }
    }

    /** Path inside the share, e.g. `Movies\Film.mkv` ("" = the share's top folder). */
    private fun pathOf(uri: Uri) = uri.pathSegments.drop(1).joinToString("\\")

    /** Runs [block] on the drive's share; reconnects once if the cached connection went stale. */
    private fun <T> withShare(uri: Uri, block: (DiskShare) -> T): T {
        val drive = forUri(uri) ?: throw IOException("This network drive was removed")
        val cached = shares[drive]
        return try {
            try {
                block(connect(drive))
            } catch (e: Exception) {
                // Retry only when a kept connection died (Wi-Fi switch, server restart), not on fresh failures.
                if (cached == null || cached.isConnected || (e !is IOException && e !is SMBRuntimeException)) throw e
                shares.remove(drive)
                block(connect(drive))
            }
        } catch (e: SMBApiException) {
            throw IOException(explain(e, drive), e)
        } catch (e: SMBRuntimeException) {
            throw IOException(e.message ?: "Network drive error", e)
        }
    }

    @Synchronized
    private fun connect(drive: NetworkDrive): DiskShare {
        shares[drive]?.takeIf { it.isConnected }?.let { return it }
        try {
            val port = drive.host.substringAfterLast(':', "").toIntOrNull()
            val host = if (port != null) drive.host.substringBeforeLast(':') else drive.host
            val connection = if (port != null) client.connect(host, port) else client.connect(host)
            // No user name: the server's guest account (Samba "guest ok", Windows guest shares).
            val auth = if (drive.user.isEmpty()) AuthenticationContext.guest()
            else AuthenticationContext(drive.user, drive.password.toCharArray(), drive.domain.ifEmpty { null })
            val share = connection.authenticate(auth).connectShare(drive.share) as? DiskShare
                ?: throw IOException("“${drive.share}” is not a shared folder")
            shares[drive] = share
            return share
        } catch (e: SMBApiException) {
            throw IOException(explain(e, drive), e)
        }
    }

    private fun explain(e: SMBApiException, drive: NetworkDrive): String = when (e.status) {
        NtStatus.STATUS_LOGON_FAILURE -> "Wrong user name or password"
        NtStatus.STATUS_ACCESS_DENIED -> "Access denied"
        NtStatus.STATUS_BAD_NETWORK_NAME -> "${drive.host} has no shared folder named “${drive.share}”"
        NtStatus.STATUS_OBJECT_NAME_NOT_FOUND, NtStatus.STATUS_OBJECT_PATH_NOT_FOUND -> "Not found on the drive"
        else -> e.status.name
    }
}
