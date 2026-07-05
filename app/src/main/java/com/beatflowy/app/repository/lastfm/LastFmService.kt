package com.beatflowy.app.repository.lastfm

import com.google.gson.annotations.SerializedName
import retrofit2.http.*

interface LastFmService {
    @GET("?format=json")
    suspend fun getTrackInfo(
        @Query("method") method: String = "track.getInfo",
        @Query("api_key") apiKey: String,
        @Query("artist") artist: String,
        @Query("track") track: String,
        @Query("username") username: String? = null
    ): LastFmTrackInfoResponse

    @GET("?format=json")
    suspend fun getArtistInfo(
        @Query("method") method: String = "artist.getInfo",
        @Query("api_key") apiKey: String,
        @Query("artist") artist: String,
        @Query("lang") lang: String? = "en"
    ): LastFmArtistInfoResponse

    @GET("?format=json")
    suspend fun getAlbumInfo(
        @Query("method") method: String = "album.getInfo",
        @Query("api_key") apiKey: String,
        @Query("artist") artist: String,
        @Query("album") album: String
    ): LastFmAlbumInfoResponse

    @FormUrlEncoded
    @POST("?format=json")
    suspend fun scrobble(
        @Field("method") method: String = "track.scrobble",
        @Field("api_key") apiKey: String,
        @Field("api_sig") apiSig: String,
        @Field("sk") sessionKey: String,
        @Field("artist[0]") artist: String,
        @Field("track[0]") track: String,
        @Field("timestamp[0]") timestamp: Long,
        @Field("album[0]") album: String? = null,
        @Field("duration[0]") duration: Long? = null
    ): LastFmScrobbleResponse

    @FormUrlEncoded
    @POST("?format=json")
    suspend fun updateNowPlaying(
        @Field("method") method: String = "track.updateNowPlaying",
        @Field("api_key") apiKey: String,
        @Field("api_sig") apiSig: String,
        @Field("sk") sessionKey: String,
        @Field("artist") artist: String,
        @Field("track") track: String,
        @Field("album") album: String? = null,
        @Field("duration") duration: Long? = null
    ): LastFmNowPlayingResponse

    @GET("?format=json")
    suspend fun getSession(
        @Query("method") method: String = "auth.getSession",
        @Query("api_key") apiKey: String,
        @Query("token") token: String,
        @Query("api_sig") apiSig: String
    ): LastFmSessionResponse
}

data class LastFmTrackInfoResponse(val track: LastFmTrack?)
data class LastFmTrack(
    val name: String,
    val artist: LastFmArtist,
    val album: LastFmAlbum?,
    val duration: String?,
    val listeners: String?,
    val playcount: String?,
    val userplaycount: String?,
    val userloved: String?,
    val toptags: LastFmTopTags?,
    val wiki: LastFmWiki?,
    val image: List<LastFmImage>? = null
)

data class LastFmArtist(val name: String, val mbid: String?, val url: String?, val image: List<LastFmImage>?)
data class LastFmAlbum(
    val artist: String, 
    val title: String, 
    val mbid: String?, 
    val url: String?, 
    val image: List<LastFmImage>?,
    val tracks: LastFmAlbumTracks?
)
data class LastFmAlbumTracks(val track: List<LastFmTrackShort>?)
data class LastFmTrackShort(val name: String, val duration: String?, val url: String?)

data class LastFmTopTags(val tag: List<LastFmTag>?)
data class LastFmTag(val name: String, val url: String?)
data class LastFmWiki(val published: String?, val summary: String?, val content: String?)

data class LastFmImage(
    @SerializedName("#text") val url: String,
    val size: String
)

data class LastFmArtistInfoResponse(val artist: LastFmArtistDetail?)
data class LastFmArtistDetail(
    val name: String,
    val mbid: String?,
    val url: String?,
    val image: List<LastFmImage>?,
    val stats: LastFmArtistStats?,
    val similar: LastFmSimilarArtists?,
    val tags: LastFmTopTags?,
    val bio: LastFmWiki?
)

data class LastFmArtistStats(val listeners: String?, val playcount: String?)
data class LastFmSimilarArtists(val artist: List<LastFmArtistShort>?)
data class LastFmArtistShort(val name: String, val url: String?, val image: List<LastFmImage>?)

data class LastFmAlbumInfoResponse(val album: LastFmAlbum?)

data class LastFmScrobbleResponse(val scrobbles: LastFmScrobbles?)
data class LastFmScrobbles(
    @SerializedName("@attr") val attr: LastFmScrobbleAttr?,
    val scrobble: List<LastFmScrobbleResult>?
)
data class LastFmScrobbleAttr(val accepted: Int, val ignored: Int)
data class LastFmScrobbleResult(val artist: LastFmText, val album: LastFmText, val track: LastFmText, val timestamp: String, val ignoredMessage: LastFmIgnoredMessage?)
data class LastFmText(@SerializedName("#text") val text: String)
data class LastFmIgnoredMessage(val code: String, @SerializedName("#text") val text: String)

data class LastFmNowPlayingResponse(val nowplaying: LastFmNowPlaying?)
data class LastFmNowPlaying(val artist: LastFmText, val album: LastFmText, val track: LastFmText, val ignoredMessage: LastFmIgnoredMessage?)

data class LastFmSessionResponse(val session: LastFmSession?)
data class LastFmSession(val name: String, val key: String, val subscriber: Int)
