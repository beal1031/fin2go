package io.github.beal1031.fin2go.data.account

import kotlinx.serialization.Serializable

/** A signed-in user on a Jellyfin server, with the token for that user. */
@Serializable
data class Account(
    val serverId: String,
    val serverUrl: String,
    val serverName: String,
    val userId: String,
    val userName: String,
    val accessToken: String,
) {
    /** Unique across servers, because user ids are only unique per server. */
    val id: String get() = "$serverId/$userId"
}
