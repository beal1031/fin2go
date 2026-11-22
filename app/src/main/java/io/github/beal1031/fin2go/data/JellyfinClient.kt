package io.github.beal1031.fin2go.data

import android.content.Context
import org.jellyfin.sdk.Jellyfin
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.createJellyfin
import org.jellyfin.sdk.model.ClientInfo

/** Holds the Jellyfin SDK and the one API client of the app, both created on first use. */
object JellyfinClient {
    private var jellyfin: Jellyfin? = null
    private var api: ApiClient? = null

    /** The SDK itself, e.g. for turning a typed address into URLs to try. */
    fun jellyfin(context: Context): Jellyfin =
        jellyfin ?: createJellyfin {
            this.context = context.applicationContext
            clientInfo = ClientInfo(name = "Fin2Go", version = "0.1")
        }.also { jellyfin = it }

    /** The client for all server calls. Server URL and token are set on it after login. */
    fun api(context: Context): ApiClient =
        api ?: jellyfin(context).createApi().also { api = it }
}
