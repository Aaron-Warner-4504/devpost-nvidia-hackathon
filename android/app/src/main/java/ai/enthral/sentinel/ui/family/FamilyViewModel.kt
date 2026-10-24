package ai.enthral.sentinel.ui.family

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ai.enthral.sentinel.AppGraph
import ai.enthral.sentinel.data.api.ApiResult
import ai.enthral.sentinel.data.repo.FamilyRepository
import ai.enthral.sentinel.data.system.FcmTokenProvider
import ai.enthral.sentinel.domain.FamilyLinks
import ai.enthral.sentinel.domain.Invite
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class FamilyError { Offline, InvalidCode, RateLimited, GuardianLimit, InvalidInput, Server }

fun ApiResult<*>.toFamilyError(): FamilyError? = when (this) {
    is ApiResult.Success -> null
    is ApiResult.NetworkError -> FamilyError.Offline
    is ApiResult.HttpError -> when (status) {
        404 -> FamilyError.InvalidCode
        429 -> FamilyError.RateLimited
        409 -> FamilyError.GuardianLimit
        422 -> FamilyError.InvalidInput
        else -> FamilyError.Server
    }
}

sealed interface LinksState {
    data object Loading : LinksState
    data object Failed : LinksState
    data class Ready(val links: FamilyLinks) : LinksState
}

data class FamilyUiState(
    // "Someone protects me"
    val consent: Boolean = false,
    val invite: Invite? = null,
    val remainingSeconds: Long = 0,
    val creating: Boolean = false,
    val inviteError: FamilyError? = null,
    // "I protect someone"
    val code: String = "",
    val name: String = "",
    val accepting: Boolean = false,
    val acceptError: FamilyError? = null,
    val accepted: Boolean = false,
    // shared
    val links: LinksState = LinksState.Loading,
    val workingLinkId: String? = null,
    val unlinkError: FamilyError? = null,
) {
    val inviteExpired: Boolean get() = invite != null && remainingSeconds <= 0
    val canAccept: Boolean get() = !accepting && code.length == CODE_LENGTH && name.isNotBlank()

    companion object {
        const val CODE_LENGTH = 6
    }
}

/** Alphabet of invite codes (no 0/O/1/I/L), as in docs/api-contract.md. */
const val CODE_ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789"

/** Upper-cases, drops anything outside the alphabet (spaces, dashes from pasting) and caps at 6. */
fun normalizeInviteCode(raw: String): String =
    raw.uppercase().filter { it in CODE_ALPHABET }.take(FamilyUiState.CODE_LENGTH)

class FamilyViewModel(
    private val family: FamilyRepository,
    private val fcm: FcmTokenProvider,
    private val clock: () -> Instant,
) : ViewModel() {
    private val _state = MutableStateFlow(FamilyUiState())
    val state: StateFlow<FamilyUiState> = _state.asStateFlow()

    init {
        loadLinks()
    }

    fun loadLinks() {
        viewModelScope.launch {
            when (val r = family.links()) {
                is ApiResult.Success -> _state.update { it.copy(links = LinksState.Ready(r.value)) }
                else -> _state.update { if (it.links is LinksState.Ready) it else it.copy(links = LinksState.Failed) }
            }
        }
    }

    // --- someone protects me ---------------------------------------------------------------------------

    fun setConsent(on: Boolean) = _state.update { it.copy(consent = on) }

    fun createInvite() {
        val s = _state.value
        if (!s.consent || s.creating) return // the API is never called without the explicit consent switch
        _state.update { it.copy(creating = true, inviteError = null) }
        viewModelScope.launch {
            when (val r = family.createInvite()) {
                is ApiResult.Success -> _state.update {
                    it.copy(creating = false, invite = r.value, remainingSeconds = remaining(r.value))
                }
                else -> _state.update { it.copy(creating = false, inviteError = r.toFamilyError()) }
            }
        }
    }

    /** Driven by the UI once per second while the screen is visible. */
    fun tick() = _state.update { s -> s.invite?.let { s.copy(remainingSeconds = remaining(it)) } ?: s }

    private fun remaining(invite: Invite): Long =
        Duration.between(clock(), invite.expiresAt).seconds.coerceAtLeast(0)

    // --- I protect someone -------------------------------------------------------------------------------

    fun onCodeChange(raw: String) = _state.update { it.copy(code = normalizeInviteCode(raw), acceptError = null, accepted = false) }
    fun onNameChange(name: String) = _state.update { it.copy(name = name.take(80), acceptError = null) }

    fun accept() {
        val s = _state.value
        if (!s.canAccept) return
        _state.update { it.copy(accepting = true, acceptError = null) }
        viewModelScope.launch {
            val r = family.accept(s.code, s.name.trim(), fcm.token())
            if (r is ApiResult.Success) {
                _state.update { it.copy(accepting = false, accepted = true, code = "", name = "") }
                loadLinks()
            } else {
                _state.update { it.copy(accepting = false, acceptError = r.toFamilyError()) }
            }
        }
    }

    // --- shared ---------------------------------------------------------------------------------------

    fun unlink(id: String) {
        _state.update { it.copy(workingLinkId = id, unlinkError = null) }
        viewModelScope.launch {
            val r = family.unlink(id)
            _state.update { it.copy(workingLinkId = null, unlinkError = r.toFamilyError()) }
            if (r is ApiResult.Success) loadLinks()
        }
    }

    fun dismissUnlinkError() = _state.update { it.copy(unlinkError = null) }

    companion object {
        fun create(g: AppGraph) = FamilyViewModel(g.family, g.fcm, g.clock)
    }
}
