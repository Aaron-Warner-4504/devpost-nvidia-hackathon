package ai.enthral.sentinel.ui.components

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import ai.enthral.sentinel.R
import ai.enthral.sentinel.domain.Tier

/** How a tier is shown besides colour: a distinct shape/icon and a text label (never colour-only). */
data class TierPresentation(@StringRes val label: Int, @DrawableRes val icon: Int)

fun Tier.presentation(): TierPresentation = when (this) {
    Tier.Info -> TierPresentation(R.string.tier_info, R.drawable.ic_tier_info)
    Tier.Caution -> TierPresentation(R.string.tier_caution, R.drawable.ic_tier_caution)
    Tier.High -> TierPresentation(R.string.tier_high, R.drawable.ic_tier_high)
    Tier.Critical -> TierPresentation(R.string.tier_critical, R.drawable.ic_tier_critical)
}
