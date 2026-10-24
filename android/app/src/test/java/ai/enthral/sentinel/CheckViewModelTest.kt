package ai.enthral.sentinel

import androidx.lifecycle.SavedStateHandle
import ai.enthral.sentinel.data.api.ApiResult
import ai.enthral.sentinel.data.system.ShareIntake
import ai.enthral.sentinel.domain.SharedContent
import ai.enthral.sentinel.testing.FakeChecks
import ai.enthral.sentinel.testing.FakeImages
import ai.enthral.sentinel.testing.FakeLocale
import ai.enthral.sentinel.testing.NetworkDown
import ai.enthral.sentinel.testing.httpError
import ai.enthral.sentinel.ui.check.CheckError
import ai.enthral.sentinel.ui.check.CheckEvent
import ai.enthral.sentinel.ui.check.CheckMode
import ai.enthral.sentinel.ui.check.CheckViewModel
import ai.enthral.sentinel.ui.check.toCheckError
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class CheckViewModelTest {
    @get:Rule val main = MainDispatcherRule()

    private val checks = FakeChecks()
    private val images = FakeImages()
    private val share = ShareIntake()
    private fun vm(saved: SavedStateHandle = SavedStateHandle()) =
        CheckViewModel(saved, checks, images, FakeLocale("hi"), share)

    @Test fun `http and network failures map to friendly errors`() {
        val cases = listOf(
            Triple(httpError(401), false, CheckError.AuthFailed),
            Triple(httpError(413), true, CheckError.ImageTooLarge),
            Triple(httpError(413), false, CheckError.TextTooLong),
            Triple(httpError(415), true, CheckError.UnsupportedImage),
            Triple(httpError(422, "no text could be found in the image"), true, CheckError.NoTextFound),
            Triple(httpError(422, "String should have at most 10000 characters"), false, CheckError.InvalidInput),
            Triple(httpError(429, "rate limit exceeded"), false, CheckError.RateLimited),
            Triple(httpError(502, "could not read the image"), true, CheckError.ImageUnreadable),
            Triple(httpError(500), false, CheckError.Server),
            Triple(NetworkDown, false, CheckError.Offline),
        )
        for ((result, screenshot, expected) in cases) {
            assertEquals("$result", expected, (result as ApiResult<*>).toCheckError(screenshot))
        }
        assertNull(ApiResult.Success(1).toCheckError(false))
    }

    @Test fun `submit needs text and sends it with the app language`() = runTest {
        val vm = vm()
        assertFalse(vm.state.value.canSubmit)
        vm.setText("  Pay now or be arrested  ")
        vm.setSender("+919876543210")
        assertTrue(vm.state.value.canSubmit)
        vm.submit()
        assertEquals(Triple("Pay now or be arrested", "+919876543210", "hi"), checks.texts.single())
    }

    @Test fun `success clears the form and opens the result`() = runTest {
        val vm = vm()
        val events = collectEvents(vm.events.flow)
        vm.setText("hello")
        vm.submit()
        assertEquals(listOf<CheckEvent>(CheckEvent.OpenResult("chk_1")), events)
        assertEquals("", vm.state.value.text)
        assertNull(vm.state.value.error)
    }

    @Test fun `the typed text survives every kind of error`() = runTest {
        val vm = vm()
        vm.setText("keep me")
        vm.setSender("Bank")
        for (failure in listOf(httpError(429), httpError(500), NetworkDown, httpError(401))) {
            checks.result = failure
            vm.submit()
            assertEquals("keep me", vm.state.value.text)
            assertEquals("Bank", vm.state.value.sender)
            assertTrue(vm.state.value.error != null)
            assertFalse(vm.state.value.loading)
        }
    }

    @Test fun `too long text is rejected locally`() = runTest {
        val vm = vm()
        vm.setText("x".repeat(CheckViewModel.MAX_TEXT + 1))
        vm.submit()
        assertEquals(CheckError.TextTooLong, vm.state.value.error)
        assertTrue(checks.texts.isEmpty())
    }

    @Test fun `cancel stops waiting and keeps the input`() = runTest {
        val gate = CompletableDeferred<Unit>()
        checks.gate = gate
        val vm = vm()
        vm.setText("slow one")
        vm.submit()
        assertTrue(vm.state.value.loading)
        vm.cancel()
        assertFalse(vm.state.value.loading)
        assertEquals("slow one", vm.state.value.text)
    }

    @Test fun `screenshot mode sends the prepared bytes`() = runTest {
        val vm = vm()
        vm.pickImage("content://media/1")
        assertEquals(CheckMode.Screenshot, vm.state.value.mode)
        vm.submit()
        assertEquals(1024 to "image/png", checks.images.single())
    }

    @Test fun `an unreadable image is a read error and keeps the picture selected`() = runTest {
        images.prepared = null
        val vm = vm()
        vm.pickImage("content://media/1")
        vm.submit()
        assertEquals(CheckError.ReadFailed, vm.state.value.error)
        assertEquals("content://media/1", vm.state.value.imageUri)
    }

    @Test fun `text and screenshot shared from another app pre-fill the form`() = runTest {
        share.offer(SharedContent("Dear customer, your KYC expires", null))
        val a = vm()
        assertEquals("Dear customer, your KYC expires", a.state.value.text)
        assertNull(share.pending.value)

        val share2 = ShareIntake().also { it.offer(SharedContent(null, "content://media/9")) }
        val b = CheckViewModel(SavedStateHandle(), checks, images, FakeLocale("en"), share2)
        assertEquals(CheckMode.Screenshot, b.state.value.mode)
        assertEquals("content://media/9", b.state.value.imageUri)
    }

    @Test fun `input is restored from saved state (process death)`() = runTest {
        val saved = SavedStateHandle()
        vm(saved).apply { setText("draft"); setSender("Ravi") }
        val restored = vm(saved)
        assertEquals("draft", restored.state.value.text)
        assertEquals("Ravi", restored.state.value.sender)
    }
}
