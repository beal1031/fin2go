package io.github.beal1031.fin2go.data.account

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json

private val Context.accountDataStore by preferencesDataStore(name = "accounts")

/** Saves the signed-in account as JSON in the app's private storage. */
class AccountStore(context: Context) {
    private val dataStore = context.applicationContext.accountDataStore
    private val accountKey = stringPreferencesKey("account")

    /** The signed-in account, or null when nobody is signed in. */
    val account: Flow<Account?> = dataStore.data.map { prefs ->
        prefs[accountKey]?.let { Json.decodeFromString<Account>(it) }
    }

    suspend fun save(account: Account) {
        dataStore.edit { it[accountKey] = Json.encodeToString(account) }
    }

    suspend fun clear() {
        dataStore.edit { it.remove(accountKey) }
    }
}
