/*
 * SPDX-FileCopyrightText: 2024-2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.twelve.datasources.innertube

import android.util.Log
import com.arturo254.innertube.YouTube
import com.arturo254.innertube.models.SongItem
import com.arturo254.innertube.models.WatchEndpoint
import com.arturo254.innertube.models.YouTubeClient
import com.arturo254.innertube.models.YouTubeLocale
import com.arturo254.innertube.pages.AlbumPage
import com.arturo254.innertube.pages.ArtistPage
import com.arturo254.innertube.pages.HomePage
import com.arturo254.innertube.pages.PlaylistPage
import com.arturo254.innertube.pages.SearchResult
import java.util.Locale

/**
 * Thin wrapper around OpenTune's [YouTube] singleton that:
 *  1. Applies the optional cookie for authenticated sessions.
 *  2. Resolves a playable stream URL from a YouTube video ID (ad-free).
 *  3. Provides convenience suspend methods matching Twelve's domain model.
 *
 * Stream URL resolution strategy (in priority order):
 *  1. [YouTubeClient.ANDROID_VR_NO_AUTH] – direct `url` fields, no cipher, the best choice.
 *  2. [YouTubeClient.IOS] – also direct URLs, good fallback.
 *  3. [YouTubeClient.WEB_REMIX] – last resort.
 */
class InnerTubeClient(private val cookie: String? = null) {
    companion object {
        private const val TAG = "InnerTubeClient"

        @Volatile
        private var cachedVisitorData: String? = null
    }

    init {
        val locale = Locale.getDefault()
        YouTube.locale = YouTubeLocale(
            gl = locale.country.takeIf { it.isNotEmpty() } ?: "US",
            hl = locale.language.takeIf { it.isNotEmpty() } ?: "en",
        )
        if (!cookie.isNullOrBlank()) {
            YouTube.cookie = cookie
        }
    }

    /**
     * Ensures [YouTube.visitorData] is populated before any API call.
     * This is the most common reason for blank results — YouTube Music ignores
     * requests without a valid visitorData token.
     */

    private suspend fun ensureVisitorData() {
        if (cachedVisitorData != null) {
            YouTube.visitorData = cachedVisitorData
            return
        }
        if (!YouTube.visitorData.isNullOrEmpty()) {
            cachedVisitorData = YouTube.visitorData
            return
        }

        YouTube.visitorData().onSuccess { token ->
            cachedVisitorData = token
            YouTube.visitorData = token
            Log.d(TAG, "visitorData fetched successfully")
        }.onFailure { e ->
            Log.w(TAG, "Failed to fetch visitorData: ${e.message}")
        }
    }

    /**
     * Returns a direct HTTPS stream URL for [videoId] for ExoPlayer, or null if unplayable.
     * Tries clients in order: ANDROID_VR_NO_AUTH → IOS → WEB_REMIX.
     *
     * Prefers audio-only adaptive formats (highest bitrate, no video track).
     */
    suspend fun getStreamUrl(videoId: String): String? {
        ensureVisitorData()
        // Use ANDROID_VR_NO_AUTH only — it's the most reliable client for anonymous
        // audio-only streams and doesn't require signature timestamps.
        // Removed multi-client fallback: serial retries were causing 30s+ delays on failure.
        return resolveUrl(videoId, YouTubeClient.ANDROID_VR_NO_AUTH)
            ?: resolveUrl(videoId, YouTubeClient.IOS)
            ?: run {
                Log.w(TAG, "Failed to resolve stream URL for $videoId")
                null
            }
    }

    private suspend fun resolveUrl(videoId: String, client: YouTubeClient): String? =
        runCatching {
            YouTube.player(
                videoId = videoId,
                playlistId = null,
                client = client,
                signatureTimestamp = null,
            ).getOrNull()
        }.getOrNull()?.let { playerResponse ->
            if (playerResponse.playabilityStatus.status != "OK") return null
            val streamingData = playerResponse.streamingData ?: return null

            val bestAudio = streamingData.adaptiveFormats
                .filter { it.isAudio && it.url != null }
                .maxByOrNull { it.bitrate }
            val bestCombined = streamingData.formats
                ?.filter { it.url != null }
                ?.maxByOrNull { it.bitrate }

            (bestAudio ?: bestCombined)?.url?.also {
                Log.d(TAG, "Resolved stream for $videoId via ${client.clientName}")
            }
        }

    /**
     * Fetches full song metadata for [videoId] using the InnerTube `next` endpoint.
     *
     * Returns a [SongItem] with title, artists, album, duration and thumbnail,
     * or null if the request fails. Used by [audio()] to populate Now Playing metadata
     * (title, artist, thumbnail) alongside the resolved stream URL.
     */

    suspend fun getSongInfo(videoId: String): SongItem? {
        ensureVisitorData()
        return YouTube.next(WatchEndpoint(videoId = videoId)).onFailure {
            Log.w(TAG, "Failed to fetch song info: ${it.message}")
        }.getOrNull()?.let { nextResult ->
            val idx = nextResult.currentIndex ?: 0
            nextResult.items.getOrNull(idx)
        }
    }

    /* Fetches the YouTube Music home page. */
    suspend fun getHomePage(): HomePage? {
        ensureVisitorData()
        return YouTube.home().onFailure {
            Log.w(TAG, "Failed to fetch home page: ${it.message}")
        }.getOrNull()
    }

    /* Fetches album metadata and track list for [browseId] */
    suspend fun getAlbum(browseId: String): AlbumPage? {
        ensureVisitorData()
        return YouTube.album(browseId, withSongs = true).onFailure {
            Log.w(TAG, "Failed to fetch album: ${it.message}")
        }.getOrNull()
    }

    /* Fetches artist page for [channelId] / browseId. */
    suspend fun getArtist(channelId: String): ArtistPage? {
        ensureVisitorData()
        return YouTube.artist(channelId).onFailure {
            Log.w(TAG, "Failed to fetch artist: ${it.message}")
        }.getOrNull()
    }

    /* Fetches the playlist by [playlistId]. */
    suspend fun getPlaylist(playlistId: String): PlaylistPage? {
        ensureVisitorData()
        return YouTube.playlist(playlistId).onFailure {
            Log.w(TAG, "Failed to fetch playlist: ${it.message}")
        }.getOrNull()
    }

    /**
     * Fetches lyrics for [videoId].
     * Returns the lyrics as a single string, or null if not found.
     */
    suspend fun getLyrics(videoId: String): String? {
        ensureVisitorData()
        val nextResult = YouTube.next(WatchEndpoint(videoId)).getOrNull()
        val lyricsEndpoint = nextResult?.lyricsEndpoint ?: return null
        return YouTube.lyrics(lyricsEndpoint).getOrNull()
    }

    /**
     * Searches YouTube Music for [query], merging songs, albums and artists.
     * Returns null on total failure; partial results (e.g. only songs) are still returned.
     */
    suspend fun search(query: String): SearchResult? {
        ensureVisitorData()
        val songs =
            YouTube.search(query, YouTube.SearchFilter.FILTER_SONG).onFailure {
                Log.w(TAG, "Failed to fetch search results: ${it.message}")
            }.getOrNull() ?: return null

        val albums =
            YouTube.search(query, YouTube.SearchFilter.FILTER_ALBUM).getOrNull()?.items.orEmpty()

        val artists = YouTube.search(query, YouTube.SearchFilter.FILTER_ARTIST)
            .getOrNull()?.items.orEmpty()

        return SearchResult(
            items = songs.items + albums + artists,
            continuation = songs.continuation
        )
    }
}
