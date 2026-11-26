package app.tgplayer.data

/** What a Library file is, worked out from its name and folders. */
sealed interface Parsed {
    data class Movie(val name: String, val year: Int?) : Parsed
    data class Episode(val series: String, val year: Int?, val season: Int, val episode: Int) : Parsed
}

/**
 * Names → movies and episodes, following Jellyfin's Emby.Naming rules (NamingOptions.cs): its
 * CleanDateTimes / CleanStrings expressions verbatim, and the common Kodi-style episode patterns
 * (S01E02, 1x02, "Season 1 Episode 2", episode files inside "Season N" folders).
 * tails: subset of Jellyfin's episode rules (no date-based, absolute-number anime or multi-episode files).
 */
object Naming {
    private val ic = setOf(RegexOption.IGNORE_CASE)

    private val cleanDateTimes = listOf(
        """(.+[^_\,\.\(\)\[\]\-])[_\.\(\)\[\]\-](19[0-9]{2}|20[0-9]{2})(?![0-9]+|\W[0-9]{2}\W[0-9]{2})([ _\,\.\(\)\[\]\-][^0-9]|).*(19[0-9]{2}|20[0-9]{2})*""",
        """(.+[^_\,\.\(\)\[\]\-])[ _\.\(\)\[\]\-]+(19[0-9]{2}|20[0-9]{2})(?![0-9]+|\W[0-9]{2}\W[0-9]{2})([ _\,\.\(\)\[\]\-][^0-9]|).*(19[0-9]{2}|20[0-9]{2})*""",
    ).map { Regex(it, ic) }

    // Group 1 is Jellyfin's "cleaned" group in each.
    private val cleanStrings = listOf(
        """^\s*(.+?)[ _\,\.\(\)\[\]\-](3d|sbs|tab|hsbs|htab|mvc|HDR|HDC|UHD|UltraHD|4k|ac3|dts|custom|dc|divx|divx5|dsr|dsrip|dutch|dvd|dvdrip|dvdscr|dvdscreener|screener|dvdivx|cam|fragment|fs|hdtv|hdrip|hdtvrip|internal|limited|multi|subs|ntsc|ogg|ogm|pal|pdtv|proper|repack|rerip|retail|cd[1-9]|r5|bd5|bd|se|svcd|swedish|german|read.nfo|nfofix|unrated|ws|web-dl|telesync|ts|telecine|tc|brrip|bdrip|480p|480i|576p|576i|720p|720i|1080p|1080i|2160p|hrhd|hrhdtv|hddvd|bluray|blu-ray|x264|x265|h264|h265|xvid|xvidvd|xxx|www.www|AAC|DTS)(?=[ _\,\.\(\)\[\]\-]|$)""",
        """^\s*(.+?)((\s*\[[^\]]+\]\s*)+)(\.[^\s]+)?$""",
        """^\s*(.+?)\WE[0-9]+(-|~)E?[0-9]+(\W|$)""",
        """^\s*\[[^\]]+\](?!\.\w+$)\s*(.+)""",
        """^\s*(.+?)\s+-\s+[0-9]+\s*$""",
        """^\s*(.+?)(([-._ ](trailer|sample))|-(scene|clip|behindthescenes|deleted|deletedscene|featurette|short|interview|other|extra))$""",
    ).map { Regex(it, ic) }

    /** Jellyfin's extras rule (last CleanString): trailers, samples, featurettes… aren't library items. */
    private val extra = Regex("""(([-._ ](trailer|sample))|-(scene|clip|behindthescenes|deleted|deletedscene|featurette|short|interview|other|extra))$""", ic)
    private val extraFolders = setOf("extras", "featurettes", "samples", "sample", "trailers", "behind the scenes", "deleted scenes", "interviews", "shorts")

    private val sxe = Regex("""^(.*?)(?<![a-z0-9])s(\d{1,4})[\]\[ ._-]*e(\d{1,3})(?!\d)""", ic)
    private val seasonEpisodeWords = Regex("""^(.*?)(?<![a-z0-9])s(?:eason)?\s*(\d{1,4})\s+e(?:pisode)?\s*(\d{1,3})(?!\d)""", ic)
    private val nXm = Regex("""^(.*?)(?<![a-z0-9])(\d{1,2})x(\d{2,3})(?!\d)""", ic)
    private val episodeOnly = Regex("""^(.*?)(?<![a-z0-9])(?:ep?_?|episode\s*)(\d{1,4})(?!\d)""", ic)
    private val leadingNumber = Regex("""^(\d{1,3})(?:\s*[-._ ]|$)""")
    private val seasonFolder = Regex("""^(?:season|series|staffel|saison|temporada|stagione|sezon|s)[ ._-]*(\d{1,4})$""", ic)

    /** Null for extras (trailers, samples…), which the Library leaves out. */
    fun parse(path: String): Parsed? {
        val parts = path.split('/').filter { it.isNotEmpty() }
        val name = parts.lastOrNull()?.substringBeforeLast('.') ?: return null
        val parent = parts.getOrNull(parts.size - 2)
        val grandparent = parts.getOrNull(parts.size - 3)
        if (extra.containsMatchIn(name) || parts.dropLast(1).any { it.lowercase() in extraFolders }) return null

        val folderSeason = parent?.let(::seasonOf)
        val match = sxe.find(name) ?: seasonEpisodeWords.find(name) ?: nXm.find(name)
        val (prefix, season, episode) = when {
            match != null -> Triple(match.groupValues[1], match.groupValues[2].toInt(), match.groupValues[3].toInt())
            folderSeason != null -> {
                val ep = episodeOnly.find(name)?.let { it.groupValues[1] to it.groupValues[2].toInt() }
                    ?: leadingNumber.find(name)?.let { "" to it.groupValues[1].toInt() }
                    ?: return movie(name, parent)
                Triple(ep.first, folderSeason, ep.second)
            }
            else -> return movie(name, parent)
        }
        // Like Jellyfin, the folder layout (Show/Season 1/…) names the series first, then the file name.
        val raw = when {
            folderSeason != null && grandparent != null -> grandparent
            tidy(prefix).isNotEmpty() -> prefix
            else -> parent ?: name
        }
        val (series, year) = cleanDateTime(raw)
        return Parsed.Episode(tidy(cleanStrings.getOrNull(3)?.find(series)?.groupValues?.get(1) ?: series), year, season, episode)
    }

    private fun seasonOf(folder: String): Int? =
        if (folder.equals("specials", true)) 0 else seasonFolder.find(folder.trim())?.groupValues?.get(1)?.toInt()

    /** A movie in its own folder ("Inception (2010)/inception-720p.mkv") takes the folder's year and name. */
    private fun movie(fileName: String, folder: String?): Parsed.Movie {
        val file = cleanVideoName(fileName)
        if (file.year == null && folder != null) {
            val dir = cleanVideoName(folder)
            if (dir.year != null) return dir
        }
        return file
    }

    /** Jellyfin's VideoResolver: CleanDateTime, then every CleanString in turn. */
    private fun cleanVideoName(raw: String): Parsed.Movie {
        var (name, year) = cleanDateTime(raw)
        for (r in cleanStrings) r.find(name)?.let { name = it.groupValues[1].trim() }
        return Parsed.Movie(tidy(name).ifEmpty { tidy(raw) }, year)
    }

    private fun cleanDateTime(name: String): Pair<String, Int?> {
        for (r in cleanDateTimes) {
            val m = r.find(name) ?: continue
            m.groupValues[2].toIntOrNull()?.let { return m.groupValues[1].trimEnd() to it }
        }
        return name to null
    }

    /** Dots/underscores to spaces, stray separators off the ends. */
    private fun tidy(s: String) = s.replace('.', ' ').replace('_', ' ').replace(Regex("\\s+"), " ").trim(' ', '-', '[', '(')
}
