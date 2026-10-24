package ai.enthral.sentinel.data.demo

import ai.enthral.sentinel.data.api.ApiResult
import ai.enthral.sentinel.domain.Alert

/** One step of the in-app demo and what happened to it. */
data class DemoStep(val key: DemoKey, val outcome: DemoOutcome)

enum class DemoKey { CourierSms, UnknownCall, WhatsappUpi }

sealed interface DemoOutcome {
    data object Pending : DemoOutcome
    data object Running : DemoOutcome
    data class Flagged(val alert: Alert) : DemoOutcome
    data object NoAlert : DemoOutcome
    data class Failed(val failure: ApiResult<Nothing>) : DemoOutcome
}
