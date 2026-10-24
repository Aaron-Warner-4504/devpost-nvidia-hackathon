package ai.enthral.sentinel

import ai.enthral.sentinel.domain.Signals
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.w3c.dom.Element

/**
 * Keeps three things in lock-step: the Android signal->string map, the checked-in list of backend signal names
 * (src/test/resources/backend_signals.txt) and, when the backend source is in the checkout, SIGNAL_WEIGHTS itself.
 * If the backend adds or renames a signal this fails until the map, both languages and the list are updated.
 */
class SignalsTest {
    private val checkedIn: Set<String> = javaClass.getResourceAsStream("/backend_signals.txt")!!
        .bufferedReader().readLines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }.toSet()

    @Test fun `every checked-in backend signal has a label and no stale labels remain`() {
        assertEquals(checkedIn, Signals.labels.keys)
    }

    @Test fun `checked-in list matches the backend source when it is available`() {
        val file = listOf("../../backend/app/pipeline/signals.py", "../backend/app/pipeline/signals.py", "backend/app/pipeline/signals.py")
            .map { File(it) }.firstOrNull { it.exists() }
        assumeTrue("backend source not present in this checkout", file != null)
        val block = file!!.readText().substringAfter("SIGNAL_WEIGHTS").substringAfter("{").substringBefore("\n}")
        val names = Regex("""^\s*"([a-z_]+)"\s*:\s*-?\d+""", RegexOption.MULTILINE).findAll(block).map { it.groupValues[1] }.toSet()
        assertTrue("parsed ${names.size} backend signals", names.size >= 15)
        assertEquals("update backend_signals.txt, Signals.labels and both strings.xml", names, checkedIn)
    }

    @Test fun `unknown signals fall back to a readable label`() {
        assertEquals("Some new signal", Signals.humanise("some_new_signal"))
    }
}

/** values/ and values-hi/ must define the same keys with the same format placeholders (scripts/check-strings.py does the same). */
class StringsParityTest {
    private data class Entry(val kind: String, val value: String)

    private fun load(dir: String): Map<String, Entry> {
        val f = listOf("src/main/res/$dir/strings.xml", "app/src/main/res/$dir/strings.xml").map { File(it) }.first { it.exists() }
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(f)
        val out = mutableMapOf<String, Entry>()
        val nodes = doc.documentElement.childNodes
        for (i in 0 until nodes.length) {
            val n = nodes.item(i) as? Element ?: continue
            out[n.getAttribute("name")] = Entry(n.tagName, n.textContent)
        }
        return out
    }

    private fun placeholders(s: String) = Regex("%(\\d+\\$)?[sd]").findAll(s).map { it.value.replace(Regex("^%\\d+\\$"), "%") }.sorted().toList()

    @Test fun `english and hindi have identical keys`() {
        val en = load("values"); val hi = load("values-hi")
        assertEquals("missing in hi: ${en.keys - hi.keys}", emptySet<String>(), en.keys - hi.keys)
        assertEquals("extra in hi: ${hi.keys - en.keys}", emptySet<String>(), hi.keys - en.keys)
    }

    @Test fun `placeholders match per string`() {
        val en = load("values"); val hi = load("values-hi")
        for ((k, e) in en) {
            assertEquals("kind of $k", e.kind, hi.getValue(k).kind)
            if (e.kind == "string") assertEquals("placeholders of $k", placeholders(e.value), placeholders(hi.getValue(k).value))
        }
    }

    @Test fun `no translation is left in English`() {
        val en = load("values"); val hi = load("values-hi")
        val allowedSame = setOf("tier_count", "check_counter")
        val untranslated = en.filter { (k, e) -> e.kind == "string" && k !in allowedSame && hi.getValue(k).value == e.value }.keys
        assertEquals("untranslated: $untranslated", emptySet<String>(), untranslated)
    }
}
