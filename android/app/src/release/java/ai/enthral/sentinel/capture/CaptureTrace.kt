package ai.enthral.sentinel.capture

/** Release builds: capture tracing is compiled out (see the debug copy). */
internal object CaptureTrace {
    @Suppress("UNUSED_PARAMETER")
    fun log(event: String, vararg fields: Pair<String, Any?>) = Unit
}
