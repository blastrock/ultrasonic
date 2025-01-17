// Converts Playlist entity from [org.moire.ultrasonic.api.subsonic.SubsonicAPIClient]
// to app domain entities.
@file:JvmName("APIPlaylistConverter")
package org.moire.ultrasonic.domain

import java.text.SimpleDateFormat
import kotlin.LazyThreadSafetyMode.NONE
import org.moire.ultrasonic.api.subsonic.models.Playlist as APIPlaylist
import org.moire.ultrasonic.util.Util.ifNotNull

internal val playlistDateFormat by lazy(NONE) { SimpleDateFormat.getInstance() }

fun APIPlaylist.toMusicDirectoryDomainEntity(): MusicDirectory = MusicDirectory().apply {
    name = this@toMusicDirectoryDomainEntity.name
    addAll(this@toMusicDirectoryDomainEntity.entriesList.map { it.toTrackEntity() })
}

fun APIPlaylist.toDomainEntity(): Playlist = Playlist(
    this.id, this.name, this.owner,
    this.comment, this.songCount.toString(),
    this.created.ifNotNull { playlistDateFormat.format(it.time) } ?: "",
    public
)

fun List<APIPlaylist>.toDomainEntitiesList(): List<Playlist> = this.map { it.toDomainEntity() }
