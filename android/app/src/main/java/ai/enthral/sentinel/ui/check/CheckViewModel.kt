package ai.enthral.sentinel.ui.check

import android.graphics.Bitmap
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ai.enthral.sentinel.AppGraph
import ai.enthral.sentinel.data.api.ApiResult
import ai.enthral.sentinel.data.image.ImagePreparer
import ai.enthral.sentinel.data.repo.CheckRepository
import ai.enthral.sentinel.data.system.LocaleController
import ai.enthral.sentinel.data.system.ShareIntake
import ai.enthral.sentinel.ui.common.Events
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class CheckMode { Text, Screenshot }

enum class CheckError { Offline, AuthFailed, RateLimited, TextTooLong, ImageTooLarge, UnsupportedImage,
    NoTextFound, ImageUnreadable, InvalidInput, ReadFailed, Server }

/** Maps an API failure to a user-facing error kind. [screenshot] disambiguates 413 (image vs 64 KB JSON). */
fun ApiResult<*>.toCheckError(screenshot: Boolean): CheckError? = when (this) {
    is ApiResult.Success -> null
    is ApiResult.NetworkError -> CheckError.Offline
    is ApiResult.HttpError -> when (status) {
        401 -> CheckError.AuthFailed
        413 -> if (screenshot) CheckError.ImageTooLarge else CheckError.TextTooLong
        415 -> CheckError.UnsupportedImage
        422 -> if (detail.orEmpty().contains("no text", ignoreCase = true)) CheckError.NoTextFound
        else CheckError.InvalidInput
        429 -> CheckError.RateLimited
        502 -> CheckError.ImageUnreadable
        else -> CheckError.Server
    }
}

data class CheckUiState(
    val mode: CheckMode = CheckMode.Text,
    val text: String = "",
    val sender: String = "",
    val imageUri: String? = null,
    val thumbnail: Bitmap? = null,
    val loading: Boolean = false,
    val error: CheckError? = null,
) {
    val canSubmit: Boolean
        get() = !loading && when (mode) {
            CheckMode.Text -> text.isNotBlank()
            CheckMode.Screenshot -> imageUri != null
        }
}

sealed interface CheckEvent {
    data class OpenResult(val alertId: String) : CheckEvent
}

class CheckViewModel(
    private val saved: SavedStateHandle,
    private val checks: CheckRepository,
    private val images: ImagePreparer,
    private val locale: LocaleController,
    shareIntake: ShareIntake,
) : ViewModel() {
    private val _state = MutableStateFlow(snapshot())
    val state: StateFlow<CheckUiState> = _state.asStateFlow()
    val events = Events<CheckEvent>()
    private var job: Job? = null


    init {
        saved.get<String>(KEY_IMAGE)?.let { loadThumbnail(it) }
        viewModelScope.launch {
            shareIntake.pending.collect { shared ->
                if (shared != null) {
                    applyShared(shared.text, shared.imageUri)
                    shareIntake.consume()
                }
            }
        }
    }

    private fun snapshot() = CheckUiState(
        mode = if (saved.get<String>(KEY_MODE) == CheckMode.Screenshot.name) CheckMode.Screenshot else CheckMode.Text,
        text = saved[KEY_TEXT] ?: "",
        sender = saved[KEY_SENDER] ?: "",
        imageUri = saved[KEY_IMAGE],
    )

    private fun applyShared(text: String?, imageUri: String?) {
        when {
            imageUri != null -> pickImage(imageUri)
            !text.isNullOrBlank() -> {
                setMode(CheckMode.Text)
                setText(text)
            }
        }
    }

    fun setMode(mode: CheckMode) {
        saved[KEY_MODE] = mode.name
        _state.update { it.copy(mode = mode, error = null) }
    }

    fun setText(text: String) {
        saved[KEY_TEXT] = text
        _state.update { it.copy(text = text, error = null) }
    }

    fun setSender(sender: String) {
        saved[KEY_SENDER] = sender
        _state.update { it.copy(sender = sender) }
    }

    fun pickImage(uri: String) {
        saved[KEY_IMAGE] = uri
        saved[KEY_MODE] = CheckMode.Screenshot.name
        _state.update { it.copy(mode = CheckMode.Screenshot, imageUri = uri, thumbnail = null, error = null) }
        loadThumbnail(uri)
    }

    fun clearImage() {
        saved.remove<String>(KEY_IMAGE)
        _state.update { it.copy(imageUri = null, thumbnail = null, error = null) }
    }

    private fun loadThumbnail(uri: String) {
        viewModelScope.launch {
            val bmp = images.thumbnail(uri, THUMB_PX)
            _state.update { if (it.imageUri == uri) it.copy(thumbnail = bmp) else it }
        }
    }

    fun clearAll() {
        saved.remove<String>(KEY_TEXT); saved.remove<String>(KEY_SENDER); saved.remove<String>(KEY_IMAGE)
        _state.update { it.copy(text = "", sender = "", imageUri = null, thumbnail = null, error = null) }
    }

    fun dismissError() = _state.update { it.copy(error = null) }

    fun submit() {
        val s = _state.value
        if (!s.canSubmit) return
        if (s.mode == CheckMode.Text && s.text.length > MAX_TEXT) {
            _state.update { it.copy(error = CheckError.TextTooLong) }
            return
        }
        _state.update { it.copy(loading = true, error = null) }
        job = viewModelScope.launch {
            val result: ApiResult<ai.enthral.sentinel.domain.Alert>? = when (s.mode) {
                CheckMode.Text -> checks.checkText(s.text.trim(), s.sender, locale.current())
                CheckMode.Screenshot -> {
                    val prepared = images.prepare(s.imageUri!!)
                    if (prepared == null) null else checks.checkImage(prepared.bytes, prepared.mimeType, locale.current())
                }
            }
            val error = if (result == null) CheckError.ReadFailed else result.toCheckError(s.mode == CheckMode.Screenshot)
            _state.update { it.copy(loading = false, error = error) }
            if (error == null) {
                val alert = (result as ApiResult.Success).value
                // The typed text is kept on every error; only a successful check clears the form.
                clearAll()
                events.send(CheckEvent.OpenResult(alert.id))
            }
        }
    }

    /** Cancels an in-flight check. Nothing the user typed is lost. */
    fun cancel() {
        job?.cancel()
        _state.update { it.copy(loading = false) }
    }

    companion object {
        const val MAX_TEXT = 10_000
        private const val THUMB_PX = 720
        private const val KEY_TEXT = "text"
        private const val KEY_SENDER = "sender"
        private const val KEY_IMAGE = "image"
        private const val KEY_MODE = "mode"

        fun create(g: AppGraph, saved: SavedStateHandle) =
            CheckViewModel(saved, g.checks, g.images, g.locale, g.shareIntake)
    }
}
