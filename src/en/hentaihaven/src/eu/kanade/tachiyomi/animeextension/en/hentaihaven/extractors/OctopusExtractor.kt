package eu.kanade.tachiyomi.animeextension.en.hentaihaven.extractors

import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import keiyoushi.network.get
import keiyoushi.utils.bodyString
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient

/**
 * Builds one [Video] per quality of the Octopus VP9/CMAF stream. Each variant is handed to the
 * player as a DASH manifest by [OctopusDash] so that seeking works.
 */
class OctopusExtractor(private val client: OkHttpClient) {

    suspend fun extractOctopusStream(sourceUrl: String, episodeUrl: String): List<Video> {
        val masterUrl = sourceUrl.toHttpUrl().let { url ->
            if (url.pathSegments.lastOrNull() == "playlist.m3u8") {
                url.newBuilder()
                    .setPathSegment(url.pathSize - 1, "playlist_vp9.m3u8")
                    .build()
            } else {
                url
            }
        }
        val videoHeaders = buildCdnHeaders(episodeUrl)
        val subtitles = listOfNotNull(masterUrl.resolve("s/en.vtt")?.let { Track(it.toString(), "English") })

        val lines = client.get(masterUrl, videoHeaders).bodyString().lines()
        val audioUrl = lines.firstNotNullOfOrNull { AUDIO_REGEX.find(it)?.groupValues?.get(1) }
            ?.let { masterUrl.resolve(it)?.toString() }

        return lines.zipWithNext().mapNotNull { (info, uri) ->
            if (!info.startsWith("#EXT-X-STREAM-INF:")) return@mapNotNull null
            val videoUrl = masterUrl.resolve(uri)?.toString() ?: return@mapNotNull null
            Video(
                videoTitle = RESOLUTION_REGEX.find(info)?.groupValues?.get(1)?.let { "${it}p" } ?: "Auto",
                videoUrl = OctopusDash.register(client, videoHeaders, videoUrl, audioUrl),
                headers = videoHeaders,
                subtitleTracks = subtitles,
            )
        }
    }

    private fun buildCdnHeaders(episodeUrl: String): Headers {
        val origin = episodeUrl.toHttpUrl().let { "${it.scheme}://${it.host}" }
        return Headers.Builder()
            .add("Referer", episodeUrl)
            .add("Origin", origin)
            .add("Accept-Encoding", "identity")
            .add("Cache-Control", "no-transform")
            .add("Accept", "application/x-mpegURL, application/vnd.apple.mpegurl, */*;q=0.8")
            .build()
    }

    companion object {
        private val AUDIO_REGEX = Regex("""^#EXT-X-MEDIA:(?=.*TYPE=AUDIO).*URI="([^"]+)"""")
        private val RESOLUTION_REGEX = Regex("""RESOLUTION=\d+x(\d+)""")
    }
}
