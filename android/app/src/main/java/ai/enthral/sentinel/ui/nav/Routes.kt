package ai.enthral.sentinel.ui.nav

import kotlinx.serialization.Serializable

/** Type-safe navigation destinations. Tabs first, then pushed screens. */
@Serializable data object Onboarding
@Serializable data object Home
@Serializable data object Alerts
@Serializable data object Check
@Serializable data object Family
@Serializable data object Settings
@Serializable data class AlertDetail(val id: String)
@Serializable data object Demo
