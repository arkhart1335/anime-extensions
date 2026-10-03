package eu.kanade.tachiyomi.animeextension.en.hentaihaven.extractors

import eu.kanade.tachiyomi.network.GET
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.nanohttpd.protocols.http.IHTTPSession
import org.nanohttpd.protocols.http.NanoHTTPD
import org.nanohttpd.protocols.http.response.Response
import org.nanohttpd.protocols.http.response.Response.newFixedLengthResponse
import org.nanohttpd.protocols.http.response.Status
import java.util.UUID
import kotlin.math.roundToLong

/**
 * Serves Octopus HLS variants as DASH manifests over loopback.
 *
 * Octopus segments are fMP4. The app's FFmpeg (7.1, used by both the player and the downloader)
 * keeps stale mov state after an HLS seek, so seeking past the cache never resumes. Its DASH
 * demuxer reopens the segment demuxer on every seek instead. Only the manifest is served
 * locally; segments are still fetched from the CDN.
 */
object OctopusDash : NanoHTTPD("127.0.0.1", 0) {

    private class Stream(val client: OkHttpClient, val headers: Headers, val videoUrl: String, val audioUrl: String?)

    /** Inclusive byte range, formatted as `first-last` like DASH `range` / `mediaRange`. */
    private class ByteRange(val first: Long, val last: Long) {
        override fun toString() = "$first-$last"
    }

    private class Resource(val url: String, val range: ByteRange?)

    private class Segment(val resource: Resource, val durationMs: Long)

    private class Playlist(val init: Resource, val segments: List<Segment>) {
        val totalMs = segments.sumOf { it.durationMs }
    }

    // Only registered streams are served, so the port can't be used to fetch arbitrary URLs.
    private val streams = object : LinkedHashMap<String, Stream>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Stream>?) = size > 64
    }

    @Synchronized
    fun register(client: OkHttpClient, headers: Headers, videoUrl: String, audioUrl: String?): String {
        if (!isAlive) start()
        val path = "/${UUID.nameUUIDFromBytes("$videoUrl|$audioUrl".toByteArray())}.mpd"
        streams[path] = Stream(client, headers, videoUrl, audioUrl)
        return "http://127.0.0.1:$listeningPort$path"
    }

    override fun handle(session: IHTTPSession): Response {
        val stream = synchronized(this) { streams[session.uri] }
            ?: return newFixedLengthResponse(Status.NOT_FOUND, MIME_PLAINTEXT, "Not Found")
        return try {
            val video = stream.fetch(stream.videoUrl)
            val audio = stream.audioUrl?.let { stream.fetch(it) }
            val mpd = buildString {
                append("""<?xml version="1.0" encoding="UTF-8"?><MPD xmlns="urn:mpeg:dash:schema:mpd:2011" type="static" """)
                append("""profiles="urn:mpeg:dash:profile:full:2011" minBufferTime="PT2S" mediaPresentationDuration="PT${video.totalMs / 1000.0}S"><Period>""")
                appendAdaptationSet("video", video)
                audio?.let { appendAdaptationSet("audio", it) }
                append("</Period></MPD>")
            }
            newFixedLengthResponse(Status.OK, "application/dash+xml", mpd)
        } catch (e: Exception) {
            newFixedLengthResponse(Status.INTERNAL_ERROR, MIME_PLAINTEXT, e.toString())
        }
    }

    private fun Stream.fetch(url: String): Playlist {
        val playlistUrl = url.toHttpUrl()
        val lines = client.newCall(GET(playlistUrl, headers)).execute().use { it.body.string() }.lines()

        var init: Resource? = null
        var pendingDuration = 0.0
        var pendingRange: String? = null
        // Per HLS, a BYTERANGE without an offset starts right after the previous sub-range of the same resource.
        val nextOffsets = HashMap<String, Long>()
        var elapsed = 0.0
        var elapsedMs = 0L
        val segments = mutableListOf<Segment>()

        for (raw in lines) {
            val line = raw.trim()
            when {
                line.isEmpty() -> Unit

                line.startsWith("#EXT-X-MAP:") -> if (init == null) {
                    val uri = MAP_URI_REGEX.find(line)?.groupValues?.get(1) ?: continue
                    val resolved = playlistUrl.resolve(uri)!!.toString()
                    val range = MAP_RANGE_REGEX.find(line)?.groupValues?.get(1)
                        ?.let { parseByteRange(it, nextOffsets, resolved) }
                    init = Resource(resolved, range)
                }

                line.startsWith("#EXTINF:") ->
                    pendingDuration = line.substringAfter(':').substringBefore(',').trim().toDoubleOrNull() ?: 0.0

                line.startsWith("#EXT-X-BYTERANGE:") -> pendingRange = line.substringAfter(':').trim()

                line.startsWith("#") -> Unit

                else -> {
                    val resolved = playlistUrl.resolve(line)!!.toString()
                    val range = pendingRange?.let { parseByteRange(it, nextOffsets, resolved) }
                    // Round on the running total so per-segment rounding never accumulates drift.
                    elapsed += pendingDuration
                    val endMs = (elapsed * 1000).roundToLong()
                    segments += Segment(Resource(resolved, range), endMs - elapsedMs)
                    elapsedMs = endMs
                    pendingDuration = 0.0
                    pendingRange = null
                }
            }
        }

        check(segments.isNotEmpty()) { "Media playlist has no segments" }
        return Playlist(checkNotNull(init) { "Media playlist has no EXT-X-MAP" }, segments)
    }

    /** Parses HLS `length[@offset]` into an inclusive range and records where the next one begins. */
    private fun parseByteRange(value: String, nextOffsets: MutableMap<String, Long>, url: String): ByteRange? {
        val length = value.substringBefore('@').toLongOrNull()?.takeIf { it > 0 } ?: return null
        val offset = value.substringAfter('@', "").toLongOrNull() ?: nextOffsets[url] ?: 0L
        nextOffsets[url] = offset + length
        return ByteRange(offset, offset + length - 1)
    }

    // FFmpeg maps a seek to segment `position / duration`, which is exact for fixed-length segments
    // (the shorter final one doesn't matter). Only when durations really vary is a SegmentTimeline
    // emitted so that each segment's start time stays accurate.
    private fun StringBuilder.appendAdaptationSet(type: String, playlist: Playlist) {
        val segments = playlist.segments
        val body = segments.dropLast(1).map { it.durationMs }
        val fixedLength = !USE_SEGMENT_TIMELINE || body.isEmpty() ||
            (body.max() - body.min() <= 1 && segments.last().durationMs <= body.max() + 1)

        append("""<AdaptationSet mimeType="$type/mp4"><Representation id="$type">""")
        if (fixedLength) {
            val duration = (playlist.totalMs.toDouble() / segments.size).roundToLong()
            append("""<SegmentList timescale="1000" duration="$duration">""")
        } else {
            append("""<SegmentList timescale="1000">""")
        }
        append("""<Initialization sourceURL="${playlist.init.url.escape()}"${playlist.init.range.attr("range")}/>""")

        if (!fixedLength) appendTimeline(segments)

        segments.forEach {
            append("""<SegmentURL media="${it.resource.url.escape()}"${it.resource.range.attr("mediaRange")}/>""")
        }
        append("</SegmentList></Representation></AdaptationSet>")
    }

    private fun StringBuilder.appendTimeline(segments: List<Segment>) {
        append("<SegmentTimeline>")
        var i = 0
        while (i < segments.size) {
            val duration = segments[i].durationMs
            var repeat = 0
            while (i + repeat + 1 < segments.size && segments[i + repeat + 1].durationMs == duration) repeat++
            append("<S ")
            if (i == 0) append("""t="0" """)
            append("""d="$duration"""")
            if (repeat > 0) append(""" r="$repeat"""")
            append("/>")
            i += repeat + 1
        }
        append("</SegmentTimeline>")
    }

    private fun ByteRange?.attr(name: String) = this?.let { """ $name="$it"""" } ?: ""

    // URLs are normalized by HttpUrl, so `&` is the only XML-special character they can contain.
    private fun String.escape() = replace("&", "&amp;")

    // The app's FFmpeg seeks reliably with a fixed SegmentList `duration` (what the original manifest used).
    // Flip this to emit a per-segment SegmentTimeline for playlists with varying EXTINF durations.
    private const val USE_SEGMENT_TIMELINE = false

    private val MAP_URI_REGEX = Regex("""URI="([^"]+)"""")
    private val MAP_RANGE_REGEX = Regex("""BYTERANGE="([^"]+)"""")
}
