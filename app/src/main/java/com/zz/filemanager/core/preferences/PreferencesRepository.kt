package com.zz.filemanager.core.preferences

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.zz.filemanager.core.model.BrowserLocation
import com.zz.filemanager.core.model.SortConfiguration
import com.zz.filemanager.core.model.SortDirection
import com.zz.filemanager.core.model.SortField
import com.zz.filemanager.core.model.ThemeMode
import com.zz.filemanager.core.model.ViewMode
import com.zz.filemanager.core.util.BrowserLocationCodec
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.zzPreferences by preferencesDataStore("zz_file_manager")

class PreferencesRepository(private val context: Context) : BrowserPreferences {
    private object Keys {
        val theme = stringPreferencesKey("theme")
        val viewMode = stringPreferencesKey("view_mode")
        val sortField = stringPreferencesKey("sort_field")
        val sortDirection = stringPreferencesKey("sort_direction")
        val foldersFirst = booleanPreferencesKey("folders_first")
        val showHidden = booleanPreferencesKey("show_hidden")
        val recentLocations = stringPreferencesKey("recent_locations")
        val safLocations = stringPreferencesKey("saf_locations")
        val lastLocation = stringPreferencesKey("last_location")
        val trashRetentionDays = intPreferencesKey("trash_retention_days")
    }

    val theme: Flow<ThemeMode> = context.zzPreferences.data.map { prefs -> prefs[Keys.theme]?.let { enumOrNull<ThemeMode>(it) } ?: ThemeMode.SYSTEM }
    override val viewMode: Flow<ViewMode> = context.zzPreferences.data.map { prefs -> prefs[Keys.viewMode]?.let { enumOrNull<ViewMode>(it) } ?: ViewMode.LIST }
    override val showHidden: Flow<Boolean> = context.zzPreferences.data.map { it[Keys.showHidden] ?: false }
    val foldersFirst: Flow<Boolean> = context.zzPreferences.data.map { it[Keys.foldersFirst] ?: true }
    override val sortConfiguration: Flow<SortConfiguration> = context.zzPreferences.data.map { prefs ->
        SortConfiguration(
            field = prefs[Keys.sortField]?.let { enumOrNull<SortField>(it) } ?: SortField.NAME,
            direction = prefs[Keys.sortDirection]?.let { enumOrNull<SortDirection>(it) } ?: SortDirection.ASCENDING,
            foldersFirst = prefs[Keys.foldersFirst] ?: true,
        )
    }
    val recentLocations: Flow<List<BrowserLocation>> = context.zzPreferences.data.map { BrowserLocationCodec.decodeList(it[Keys.recentLocations].orEmpty()) }
    val safLocations: Flow<List<BrowserLocation>> = context.zzPreferences.data.map { BrowserLocationCodec.decodeList(it[Keys.safLocations].orEmpty()) }
    val lastLocation: Flow<BrowserLocation?> = context.zzPreferences.data.map { it[Keys.lastLocation]?.let(BrowserLocationCodec::decode) }
    val trashRetentionDays: Flow<Int> = context.zzPreferences.data.map { it[Keys.trashRetentionDays] ?: 30 }

    suspend fun setTheme(value: ThemeMode) { context.zzPreferences.edit { it[Keys.theme] = value.name } }
    override suspend fun setViewMode(value: ViewMode) { context.zzPreferences.edit { it[Keys.viewMode] = value.name } }
    override suspend fun setShowHidden(value: Boolean) { context.zzPreferences.edit { it[Keys.showHidden] = value } }
    suspend fun setFoldersFirst(value: Boolean) { context.zzPreferences.edit { it[Keys.foldersFirst] = value } }
    override suspend fun setSortField(value: SortField) { context.zzPreferences.edit { it[Keys.sortField] = value.name } }
    override suspend fun setSortDirection(value: SortDirection) { context.zzPreferences.edit { it[Keys.sortDirection] = value.name } }
    suspend fun setLastLocation(value: BrowserLocation) { context.zzPreferences.edit { it[Keys.lastLocation] = BrowserLocationCodec.encode(value) } }
    suspend fun clearLastLocation() { context.zzPreferences.edit { it.remove(Keys.lastLocation) } }
    suspend fun setTrashRetentionDays(days: Int) { require(days in setOf(-1, 7, 30, 60, 90)); context.zzPreferences.edit { it[Keys.trashRetentionDays] = days } }

    suspend fun addRecent(location: BrowserLocation) = context.zzPreferences.edit { prefs ->
        val current = BrowserLocationCodec.decodeList(prefs[Keys.recentLocations].orEmpty())
        val next = buildList {
            add(location)
            addAll(current.filterNot { it.identity == location.identity })
        }.take(12)
        prefs[Keys.recentLocations] = BrowserLocationCodec.encodeList(next)
    }

    suspend fun clearRecentLocations() = context.zzPreferences.edit { it.remove(Keys.recentLocations) }

    suspend fun addSafLocation(location: BrowserLocation) = context.zzPreferences.edit { prefs ->
        val current = BrowserLocationCodec.decodeList(prefs[Keys.safLocations].orEmpty())
        val next = buildList {
            add(location)
            addAll(current.filterNot { it.rootReference == location.rootReference })
        }
        prefs[Keys.safLocations] = BrowserLocationCodec.encodeList(next)
    }

    suspend fun removeSafLocation(rootReference: String) = context.zzPreferences.edit { prefs ->
        val current = BrowserLocationCodec.decodeList(prefs[Keys.safLocations].orEmpty())
        prefs[Keys.safLocations] = BrowserLocationCodec.encodeList(current.filterNot { it.rootReference == rootReference })
    }

    private inline fun <reified T : Enum<T>> enumOrNull(value: String): T? = enumValues<T>().firstOrNull { it.name == value }
}
