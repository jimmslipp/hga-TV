package com.hga.media.data

import com.hga.media.util.Http

/**
 * Parses a standard extended M3U playlist. Handles the tag soup that real
 * providers emit: tvg-id / tvg-logo / group-title on the EXTINF line, separate
 * EXTGRP lines, VLC and Kodi option lines, and stray blank lines.
 */
object M3uParser {

    private val ATTR = Regex("([a-zA-Z0-9_-]+)\\s*=\\s*\"([^\"]*)\"")

    data class Result(
        val categories: List<Category>,
        val channels: List<Channel>
    )

    suspend fun fetchAndParse(url: String): Result =
        Http.streamLines(url) { lines -> parse(lines) }

    fun parse(lines: Sequence<String>): Result {
        val channels = ArrayList<Channel>()
        val groupOrder = LinkedHashSet<String>()

        var name = ""
        var logo: String? = null
        var epgId: String? = null
        var group = ""
        var lastNum = 0
        var explicitNum: Int? = null
        var haveExtinf = false

        for (rawLine in lines) {
            val line = rawLine.trim()
            if (line.isEmpty()) continue

            when {
                line.startsWith("#EXTM3U", true) -> {
                    // Playlist header. Some providers put a default tvg-url here; ignored.
                }

                line.startsWith("#EXTINF", true) -> {
                    haveExtinf = true
                    name = ""; logo = null; epgId = null; group = ""; explicitNum = null
                    val commaIndex = line.lastIndexOf(',')
                    val attrPart = if (commaIndex > 0) line.substring(0, commaIndex) else line
                    if (commaIndex > 0) name = line.substring(commaIndex + 1).trim()

                    for (m in ATTR.findAll(attrPart)) {
                        val key = m.groupValues[1].lowercase()
                        val value = m.groupValues[2].trim()
                        when (key) {
                            "tvg-id", "tvg-chno-id" -> epgId = value.ifBlank { null }
                            "tvg-logo", "logo" -> logo = value.ifBlank { null }
                            "group-title", "group" -> group = value
                            "tvg-name" -> if (name.isBlank()) name = value
                            "tvg-chno" -> explicitNum = value.toIntOrNull()
                        }
                    }
                }

                line.startsWith("#EXTGRP", true) -> {
                    group = line.substringAfter(":", "").trim()
                }

                line.startsWith("#") -> {
                    // #EXTVLCOPT, #KODIPROP, #EXTM3U comments - nothing to do.
                }

                else -> {
                    if (!haveExtinf && !looksLikeUrl(line)) continue
                    val url = line
                    if (!looksLikeUrl(url)) continue
                    val groupName = group.ifBlank { "Uncategorised" }
                    groupOrder.add(groupName)
                    // Honour the provider's own channel number when it gives one,
                    // otherwise carry on counting from the last number used.
                    val num = explicitNum ?: (lastNum + 1)
                    lastNum = num
                    channels.add(
                        Channel(
                            id = "m3u_" + channels.size,
                            num = num,
                            name = name.ifBlank { "Channel $num" },
                            logo = logo,
                            categoryId = groupKey(groupName),
                            url = url,
                            epgId = epgId,
                            kind = guessKind(url, groupName)
                        )
                    )
                    haveExtinf = false
                    name = ""; logo = null; epgId = null; group = ""; explicitNum = null
                }
            }
        }

        val categories = groupOrder.map { Category(groupKey(it), it, Kind.LIVE) }
        return Result(categories, channels)
    }

    private fun groupKey(group: String) = "g_" + group.lowercase().replace(Regex("[^a-z0-9]+"), "_")

    private fun looksLikeUrl(s: String): Boolean =
        s.startsWith("http://", true) || s.startsWith("https://", true) ||
                s.startsWith("rtmp", true) || s.startsWith("rtsp", true) ||
                s.startsWith("udp", true) || s.startsWith("file://", true)

    /**
     * M3U has no concept of live vs on-demand, so we make a sensible guess and
     * let the user override the section from the menu.
     */
    private fun guessKind(url: String, group: String): Int {
        val g = group.lowercase()
        val u = url.lowercase()
        val vodMarkers = listOf("/movie/", "/movies/", "vod")
        val seriesMarkers = listOf("/series/", "season", "s0")
        return when {
            seriesMarkers.any { g.contains(it) } || u.contains("/series/") -> Kind.SERIES
            vodMarkers.any { g.contains(it) } || u.contains("/movie/") -> Kind.VOD
            u.endsWith(".mp4") || u.endsWith(".mkv") || u.endsWith(".avi") -> Kind.VOD
            else -> Kind.LIVE
        }
    }
}
