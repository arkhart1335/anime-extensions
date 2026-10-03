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

    private class Playlist(val initUrl: String, val segmentUrls: List<String>, val seconds: Double)

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
                append("""profiles="urn:mpeg:dash:profile:full:2011" minBufferTime="PT2S" mediaPresentationDuration="PT${video.seconds}S"><Period>""")
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
        return Playlist(
            initUrl = playlistUrl.resolve(lines.firstNotNullOf { MAP_REGEX.find(it)?.groupValues?.get(1) })!!.toString(),
            segmentUrls = lines.filter { it.isNotBlank() && !it.startsWith("#") }.map { playlistUrl.resolve(it)!!.toString() },
            seconds = lines.sumOf { it.substringAfter("#EXTINF:", "").substringBefore(',').toDoubleOrNull() ?: 0.0 },
        )
    }

    // FFmpeg maps a seek to segment `position / duration`, so the average segment length is enough.
    private fun StringBuilder.appendAdaptationSet(type: String, playlist: Playlist) {
        val duration = (playlist.seconds * 1000 / playlist.segmentUrls.size).roundToLong()
        append("""<AdaptationSet mimeType="$type/mp4"><Representation id="$type">""")
        append("""<SegmentList timescale="1000" duration="$duration"><Initialization sourceURL="${playlist.initUrl.escape()}"/>""")
        playlist.segmentUrls.forEach { append("""<SegmentURL media="${it.escape()}"/>""") }
        append("</SegmentList></Representation></AdaptationSet>")
    }

    // URLs are normalized by HttpUrl, so `&` is the only XML-special character they can contain.
    private fun String.escape() = replace("&", "&amp;")

    private val MAP_REGEX = Regex("""^#EXT-X-MAP:.*URI="([^"]+)"""")
}
