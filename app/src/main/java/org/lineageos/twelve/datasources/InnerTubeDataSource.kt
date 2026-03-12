/*
 * SPDX-FileCopyrightText: 2024-2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.twelve.datasources

import android.net.Uri
import androidx.core.net.toUri
import com.arturo254.innertube.models.AlbumItem
import com.arturo254.innertube.models.ArtistItem
import com.arturo254.innertube.models.SongItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import org.lineageos.twelve.R
import org.lineageos.twelve.datasources.innertube.InnerTubeClient
import org.lineageos.twelve.models.ActivityTab
import org.lineageos.twelve.models.Album
import org.lineageos.twelve.models.Artist
import org.lineageos.twelve.models.ArtistWorks
import org.lineageos.twelve.models.Audio
import org.lineageos.twelve.models.DataSourceInformation
import org.lineageos.twelve.models.Error
import org.lineageos.twelve.models.Genre
import org.lineageos.twelve.models.GenreContent
import org.lineageos.twelve.models.LocalizedString
import org.lineageos.twelve.models.Lyrics
import org.lineageos.twelve.models.MediaItem
import org.lineageos.twelve.models.MediaType
import org.lineageos.twelve.models.Playlist
import org.lineageos.twelve.models.ProviderArgument
import org.lineageos.twelve.models.ProviderArgument.Companion.getArgument
import org.lineageos.twelve.models.ProviderIdentifier
import org.lineageos.twelve.models.ProviderType
import org.lineageos.twelve.models.Result
import org.lineageos.twelve.models.SortingRule
import org.lineageos.twelve.models.Thumbnail
import org.lineageos.twelve.repositories.ProvidersRepository

/**
 * YouTube Music data source powered by InnerTube.
 *
 * Streams audio ad-free directly from YouTube Music using the internal
 * InnerTube API. No API key required. An optional cookie can be
 * provided for authenticated access (age-restricted content / library).
 */

class InnerTubeDataSource(
    coroutineScope: CoroutineScope,
    providersRepository: ProvidersRepository
) : MediaDataSource {
    /* Inner instance - one per configured provider entry */
    private class InnerTubeInstance(
        cookie: String?
    ) : ProvidersManager.Instance {
        private val BASE_SCHEME = "youtubemusicc"
        private val BASE_URI = "$BASE_SCHEME://host".toUri()

        val albumsUri: Uri = BASE_URI.buildUpon().appendPath(ALBUMS_PATH).build()
        val artistsUri: Uri = BASE_URI.buildUpon().appendPath(ARTISTS_PATH).build()
        val audiosUri: Uri = BASE_URI.buildUpon().appendPath(AUDIOS_PATH).build()
        val playlistsUri: Uri = BASE_URI.buildUpon().appendPath(PLAYLISTS_PATH).build()

        val client = InnerTubeClient(cookie)

        override suspend fun isMediaItemCompatible(mediaItemUri: Uri) =
            mediaItemUri.scheme == BASE_SCHEME

        // URI builders
        fun getAlbumUri(browseId: String): Uri =
            albumsUri.buildUpon().appendPath(browseId).build()

        fun getArtistUri(channelId: String): Uri =
            artistsUri.buildUpon().appendPath(channelId).build()

        fun getAudioUri(videoId: String): Uri =
            audiosUri.buildUpon().appendPath(videoId).build()

        // Model mappers
        fun SongItem.toAudio(): Audio =
            Audio.Builder(getAudioUri(id))
                .setTitle(title)
                .setArtistName(artists.firstOrNull()?.name)
                .setArtistUri(artists.firstOrNull()?.id?.let { getArtistUri(it) })
                .setAlbumTitle(album?.name)
                .setAlbumUri(album?.id?.let { getAlbumUri(it) })
                .setDurationMs(duration?.toLong()?.times(1000L))
                .setThumbnail(
                    Thumbnail.Builder()
                        .setUri(thumbnail.toUri())
                        .setType(Thumbnail.Type.FRONT_COVER)
                        .build()
                ).build()

        fun AlbumItem.toAlbum(): Album =
            Album.Builder(getAlbumUri(browseId))
                .setTitle(title)
                .setArtistName(artists?.firstOrNull()?.name)
                .setArtistUri(artists?.firstOrNull()?.id?.let { getArtistUri(it) })
                .setYear(year)
                .setThumbnail(
                    Thumbnail.Builder()
                        .setUri(thumbnail.toUri())
                        .setType(Thumbnail.Type.FRONT_COVER)
                        .build()
                )
                .build()

        fun ArtistItem.toArtist(): Artist =
            Artist.Builder(getArtistUri(id))
                .setName(title)
                .setThumbnail(
                    thumbnail.takeIf { it.isNotEmpty() }?.let {
                        Thumbnail.Builder()
                            .setUri(it.toUri())
                            .setType(Thumbnail.Type.BAND_ARTIST_LOGO)
                            .build()
                    }
                )
                .build()
    }

    // ProvidersManager
    private val providersManager = ProvidersManager(
        coroutineScope = coroutineScope,
        providersRepository = providersRepository,
        providerType = ProviderType.INNERTUBE,
    ) { _, arguments ->
        InnerTubeInstance(
            cookie = arguments.getArgument(ARG_COOKIE),
        )
    }

    // MediaDataSource implementation
    override fun status(providerIdentifier: ProviderIdentifier) =
        providersManager.mapWithInstanceOf(providerIdentifier) {
            Result.Success(
                listOf(
                    DataSourceInformation(
                        key = "innertube_status",
                        keyLocalizedString = LocalizedString.StringResIdLocalizedString(
                            R.string.innertube_status_key
                        ),
                        value = LocalizedString.StringResIdLocalizedString(
                            R.string.innertube_status_connected
                        ),
                    )
                )
            )
        }

    override suspend fun mediaTypeOf(mediaItemUri: Uri): MediaType? {
        val segments = mediaItemUri.pathSegments
        return when {
            segments.size < 2 -> null
            segments[0] == ALBUMS_PATH -> MediaType.ALBUM
            segments[0] == ARTISTS_PATH -> MediaType.ARTIST
            segments[0] == AUDIOS_PATH -> MediaType.AUDIO
            segments[0] == PLAYLISTS_PATH -> MediaType.PLAYLIST
            else -> null
        }
    }

    override fun providerOf(mediaItemUri: Uri) = providersManager.providerOf(mediaItemUri)

    // Activity / Home
    override fun activity(providerIdentifier: ProviderIdentifier) =
        providersManager.mapWithInstanceOf(providerIdentifier) {
            val homePage = client.getHomePage()
                ?: return@mapWithInstanceOf Result.Success(emptyList<ActivityTab>())

            val tabs = homePage.sections.mapNotNull { section ->
                val items: List<MediaItem<*>> = section.items.mapNotNull { item ->
                    when (item) {
                        is SongItem -> item.toAudio()
                        is AlbumItem -> item.toAlbum()
                        is ArtistItem -> item.toArtist()
                        else -> null
                    }
                }.ifEmpty { return@mapNotNull null }

                ActivityTab(
                    id = "home_${section.title.hashCode()}",
                    title = LocalizedString.StringLocalizedString(section.title),
                    items = items,
                )
            }
            Result.Success(tabs)
        }

    // Albums
    override fun albums(
        providerIdentifier: ProviderIdentifier,
        sortingRule: SortingRule,
    ) = providersManager.mapWithInstanceOf(providerIdentifier) {
        Result.Success(emptyList<Album>())
    }

    override fun album(albumUri: Uri) =
        providersManager.flatMapWithInstanceOf(albumUri) {
            val browseId = albumUri.lastPathSegment
                ?: return@flatMapWithInstanceOf flowOf(Result.Error(Error.NOT_FOUND))
            flow {
                val albumPage = client.getAlbum(browseId)
                if (albumPage == null) {
                    emit(Result.Error<Pair<Album, List<Audio>>, Error>(Error.NOT_FOUND))
                    return@flow
                }
                emit(Result.Success(albumPage.album.toAlbum() to albumPage.songs.map { it.toAudio() }))
            }
        }

    // Artists
    override fun artists(
        providerIdentifier: ProviderIdentifier,
        sortingRule: SortingRule,
    ) = providersManager.mapWithInstanceOf(providerIdentifier) {
        Result.Success(emptyList<Artist>())
    }

    override fun artist(artistUri: Uri) =
        providersManager.flatMapWithInstanceOf(artistUri) {
            val channelId = artistUri.lastPathSegment
                ?: return@flatMapWithInstanceOf flowOf(Result.Error(Error.NOT_FOUND))
            flow {
                val artistPage = client.getArtist(channelId)
                if (artistPage == null) {
                    emit(Result.Error<Pair<Artist, ArtistWorks>, Error>(Error.NOT_FOUND))
                    return@flow
                }

                val artist = artistPage.artist.toArtist()

                // Extract songs and albums from ArtistPage sections
                val songs = artistPage.sections
                    .flatMap { it.items }
                    .filterIsInstance<SongItem>()
                    .map { it.toAudio() }

                val albums = artistPage.sections
                    .flatMap { it.items }
                    .filterIsInstance<AlbumItem>()
                    .map { it.toAlbum() }

                val artistWorks = ArtistWorks(
                    albums = albums,
                    appearsInAlbum = emptyList(),
                    appearsInPlaylist = emptyList(),
                )
                emit(Result.Success(artist to artistWorks))
            }
        }

// Audios

    override fun audios(
        providerIdentifier: ProviderIdentifier,
        sortingRule: SortingRule,
    ) = providersManager.mapWithInstanceOf(providerIdentifier) {
        Result.Success(emptyList<Audio>())
    }

    /**
     * Resolves the real stream URL for [audioUri] via the InnerTube player endpoint.
     * This is the core ad-free playback method.
     */
    override fun audio(audioUri: Uri) =
        providersManager.flatMapWithInstanceOf(audioUri) {
            val videoId = audioUri.lastPathSegment
                ?: return@flatMapWithInstanceOf flowOf(Result.Error(Error.NOT_FOUND))
            flow {
                val streamUrl = client.getStreamUrl(videoId)
                if (streamUrl == null) {
                    emit(Result.Error<Audio, Error>(Error.IO))
                    return@flow
                }
                val songInfo = client.getSongInfo(videoId)
                val audio = Audio.Builder(audioUri)
                    .setPlaybackUri(streamUrl.toUri())
                    .setMimeType("audio/mp4")
                    .setTitle(songInfo?.title)
                    .setArtistName(songInfo?.artists?.firstOrNull()?.name)
                    .setArtistUri(songInfo?.artists?.firstOrNull()?.id?.let { getArtistUri(it) })
                    .setAlbumTitle(songInfo?.album?.name)
                    .setAlbumUri(songInfo?.album?.id?.let { getAlbumUri(it) })
                    .setDurationMs(songInfo?.duration?.toLong()?.times(1000L))
                    .setThumbnail(
                        songInfo?.thumbnail?.let { thumbUrl ->
                            Thumbnail.Builder()
                                .setUri(thumbUrl.toUri())
                                .setType(Thumbnail.Type.FRONT_COVER)
                                .build()
                        }
                    ).build()
                emit(Result.Success(audio))
            }
        }

    // Genres
    override fun genres(
        providerIdentifier: ProviderIdentifier,
        sortingRule: SortingRule,
    ) = providersManager.mapWithInstanceOf(providerIdentifier) {
        Result.Success(emptyList<Genre>())
    }

    override fun genre(genreUri: Uri) =
        providersManager.flatMapWithInstanceOf(genreUri) {
            flowOf(Result.Error<Pair<Genre, GenreContent>, Error>(Error.NOT_FOUND))
        }

    // Playlists
    override fun playlists(
        providerIdentifier: ProviderIdentifier,
        sortingRule: SortingRule,
    ) = providersManager.mapWithInstanceOf(providerIdentifier) {
        Result.Success(emptyList<Playlist>())
    }

    override fun playlist(playlistUri: Uri) =
        providersManager.flatMapWithInstanceOf(playlistUri) {
            val playlistId = playlistUri.lastPathSegment
                ?: return@flatMapWithInstanceOf flowOf(Result.Error(Error.NOT_FOUND))
            flow {
                val page = client.getPlaylist(playlistId)
                if (page == null) {
                    emit(Result.Error<Pair<Playlist, List<Audio>>, Error>(Error.NOT_FOUND))
                    return@flow
                }
                val playlist = Playlist.Builder(playlistUri)
                    .setName(page.playlist.title)
                    .build()
                emit(Result.Success(playlist to page.songs.map { it.toAudio() }))
            }
        }

    override fun audioPlaylistsStatus(audioUri: Uri) =
        providersManager.flatMapWithInstanceOf(audioUri) {
            flowOf(Result.Success(emptyList<Pair<Playlist, Boolean>>()))
        }

    override fun lyrics(audioUri: Uri): Flow<Result<Lyrics, Error>> =
        providersManager.flatMapWithInstanceOf(audioUri) {
            val videoId = audioUri.lastPathSegment
                ?: return@flatMapWithInstanceOf flowOf(Result.Error(Error.NOT_FOUND))
            flow {
                val lyricsText = client.getLyrics(videoId)
                if (lyricsText == null) {
                    emit(Result.Error<Lyrics, Error>(Error.NOT_FOUND))
                    return@flow
                }

                val lyrics = Lyrics.Builder().apply {
                    lyricsText.lineSequence().forEach { line ->
                        addLine(line)
                    }
                }.build()

                emit(Result.Success(lyrics))
            }
        }

    // Search
    override fun search(
        providerIdentifier: ProviderIdentifier,
        query: String,
    ) = providersManager.mapWithInstanceOf(providerIdentifier) {
        val results: List<MediaItem<*>> = client.search(query)
            ?.items
            ?.mapNotNull { item ->
                when (item) {
                    is SongItem -> item.toAudio()
                    is AlbumItem -> item.toAlbum()
                    is ArtistItem -> item.toArtist()
                    else -> null
                }
            } ?: emptyList()
        Result.Success(results)
    }

    // Unsupported write operations
    override suspend fun createPlaylist(
        providerIdentifier: ProviderIdentifier,
        name: String,
    ): MediaRequestStatus<Uri> = Result.Error(Error.NOT_IMPLEMENTED)

    override suspend fun renamePlaylist(
        playlistUri: Uri,
        name: String,
    ): MediaRequestStatus<Unit> = Result.Error(Error.NOT_IMPLEMENTED)

    override suspend fun deletePlaylist(
        playlistUri: Uri,
    ): MediaRequestStatus<Unit> = Result.Error(Error.NOT_IMPLEMENTED)

    override suspend fun addAudioToPlaylist(
        playlistUri: Uri,
        audioUri: Uri,
    ): MediaRequestStatus<Unit> = Result.Error(Error.NOT_IMPLEMENTED)

    override suspend fun removeAudioFromPlaylist(
        playlistUri: Uri,
        audioUri: Uri,
    ): MediaRequestStatus<Unit> = Result.Error(Error.NOT_IMPLEMENTED)

    override suspend fun onAudioPlayed(
        audioUri: Uri,
        positionMs: Long,
    ): MediaRequestStatus<Unit> = Result.Success(Unit)

    override suspend fun setFavorite(
        audioUri: Uri,
        isFavorite: Boolean,
    ): MediaRequestStatus<Unit> = Result.Error(Error.NOT_IMPLEMENTED)

    // Companion
    companion object {
        private const val ALBUMS_PATH = "albums"
        private const val ARTISTS_PATH = "artists"
        private const val AUDIOS_PATH = "audios"
        private const val PLAYLISTS_PATH = "playlists"

        /**
         * Optional YouTube Music cookie for authenticated access.
         * When blank/null the provider works anonymously.
         */
        val ARG_COOKIE = ProviderArgument(
            key = "cookie",
            type = String::class,
            nameStringResId = R.string.innertube_arg_cookie,
            required = false,
            hidden = true,
        )
    }
}
