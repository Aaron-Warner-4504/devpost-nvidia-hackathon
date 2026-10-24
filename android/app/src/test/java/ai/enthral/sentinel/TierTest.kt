package ai.enthral.sentinel

import ai.enthral.sentinel.data.api.SentinelJson
import ai.enthral.sentinel.domain.Tier
import ai.enthral.sentinel.ui.components.presentation
import ai.enthral.sentinel.ui.theme.TierColorPair
import ai.enthral.sentinel.ui.theme.TierPalette
import kotlin.math.pow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TierTest {
    @Test fun `every tier has a distinct label and icon so colour is never the only signal`() {
        val p = Tier.entries.map { it.presentation() }
        assertEquals(4, p.map { it.label }.toSet().size)
        assertEquals(4, p.map { it.icon }.toSet().size)
        assertEquals(R.string.tier_critical, Tier.Critical.presentation().label)
        assertEquals(R.drawable.ic_tier_info, Tier.Info.presentation().icon)
    }

    @Test fun `only high and critical interrupt`() {
        assertEquals(listOf(Tier.High, Tier.Critical), Tier.entries.filter { it.interrupts })
        assertFalse(Tier.Caution.interrupts)
    }

    @Test fun `tiers use the contract wire names`() {
        val wire = Tier.entries.map { SentinelJson.encodeToString(Tier.serializer(), it).trim('"') }
        assertEquals(listOf("info", "caution", "high", "critical"), wire)
        assertEquals(Tier.Critical, SentinelJson.decodeFromString(Tier.serializer(), "\"critical\""))
    }

    private fun lum(argb: Long): Double {
        fun ch(shift: Int): Double {
            val c = ((argb shr shift) and 0xFF) / 255.0
            return if (c <= 0.03928) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * ch(16) + 0.7152 * ch(8) + 0.0722 * ch(0)
    }

    private fun contrast(p: TierColorPair): Double {
        val (a, b) = lum(p.content) to lum(p.container)
        return (maxOf(a, b) + 0.05) / (minOf(a, b) + 0.05)
    }

    @Test fun `tier colours meet 4_5 to 1 contrast in light and dark`() {
        for ((mode, palette) in listOf("light" to TierPalette.light, "dark" to TierPalette.dark)) {
            Tier.entries.forEach { t ->
                val c = contrast(palette.getValue(t))
                assertTrue("$mode $t contrast $c", c >= 4.5)
            }
        }
    }
}
