package ai.enthral.sentinel.data.store

import ai.enthral.sentinel.domain.ConsentSource
import ai.enthral.sentinel.domain.FeedbackLabel
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

class SettingsStore(private val dataStore: DataStore<Preferences>) : AppSettings {
    override val onboardingDone: Flow<Boolean> = dataStore.data.map { it[ONBOARDING_DONE] ?: false }

    override suspend fun setOnboardingDone(done: Boolean) {
        dataStore.edit { it[ONBOARDING_DONE] = done }
    }

    override fun consentIntent(source: ConsentSource): Flow<Boolean> =
        dataStore.data.map { it[consentKey(source)] ?: (source == ConsentSource.Check) }

    override suspend fun setConsentIntent(source: ConsentSource, on: Boolean) {
        dataStore.edit { it[consentKey(source)] = on || source == ConsentSource.Check }
    }

    override val monitoringPaused: Flow<Boolean> = dataStore.data.map { it[PAUSED] ?: false }

    override suspend fun setMonitoringPaused(paused: Boolean) {
        dataStore.edit { it[PAUSED] = paused }
    }

    override val reactions: Flow<Map<String, FeedbackLabel>> = dataStore.data.map { prefs ->
        prefs[REACTIONS].orEmpty().mapNotNull { entry ->
            val id = entry.substringBeforeLast('|', "")
            val label = when (entry.substringAfterLast('|')) {
                "scam" -> FeedbackLabel.Scam
                "safe" -> FeedbackLabel.Safe
                else -> null
            }
            if (id.isEmpty() || label == null) null else id to label
        }.toMap()
    }

    override suspend fun setReaction(alertId: String, label: FeedbackLabel?) {
        dataStore.edit { prefs ->
            val kept = prefs[REACTIONS].orEmpty().filterNot { it.substringBeforeLast('|') == alertId }
            val added = label?.let { "$alertId|${if (it == FeedbackLabel.Scam) "scam" else "safe"}" }
            prefs[REACTIONS] = (kept + listOfNotNull(added)).toSet()
        }
    }

    override val notifiedAlertIds: Flow<Set<String>?> = dataStore.data.map { prefs ->
        if (prefs[NOTIFIED_BASELINE] == true) prefs[NOTIFIED].orEmpty() else null
    }

    override suspend fun markNotified(ids: Set<String>) {
        dataStore.edit {
            it[NOTIFIED_BASELINE] = true
            it[NOTIFIED] = it[NOTIFIED].orEmpty() + ids
        }
    }

    override val useTrueBlack: Flow<Boolean> = dataStore.data.map { it[TRUE_BLACK] ?: false }

    override suspend fun setUseTrueBlack(enabled: Boolean) {
        dataStore.edit { it[TRUE_BLACK] = enabled }
    }

    override suspend fun installId(): String {
        dataStore.data.first()[INSTALL_ID]?.let { return it }
        val id = UUID.randomUUID().toString()
        dataStore.edit { if (it[INSTALL_ID] == null) it[INSTALL_ID] = id }
        return dataStore.data.first()[INSTALL_ID] ?: id
    }

    override suspend fun clearAll() {
        dataStore.edit { it.clear() }
    }

    private fun consentKey(source: ConsentSource) = booleanPreferencesKey("consent_${source.name.lowercase()}")

    private companion object {
        val ONBOARDING_DONE = booleanPreferencesKey("onboarding_done")
        val PAUSED = booleanPreferencesKey("monitoring_paused")
        val REACTIONS = stringSetPreferencesKey("reactions")
        val NOTIFIED = stringSetPreferencesKey("notified_alert_ids")
        val NOTIFIED_BASELINE = booleanPreferencesKey("notified_baseline")
        val TRUE_BLACK = booleanPreferencesKey("true_black")
        val INSTALL_ID = stringPreferencesKey("install_id")
    }
}
