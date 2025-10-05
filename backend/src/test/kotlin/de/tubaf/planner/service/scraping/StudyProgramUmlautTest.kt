package de.tubaf.planner.service.scraping

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

/**
 * Testet Robustheit bei Studiengang-Codes mit Umlauten (z.B. BGÖK).
 * Wir simulieren einen Link wie er auf der Seite vorkommt: stgvrz.html?stdg=BGÖK&stdg1=Irgendwas
 * und prüfen, dass parseQueryParameters den Code unverändert liefert.
 */
class StudyProgramUmlautTest {

    @Test
    fun `parseQueryParameters preserves umlaut`() {
        val href = "stgvrz.html?stdg=BGÖK&stdg1=Geologie"
        val params = parseQueryParameters(href)
        assertEquals("BGÖK", params["stdg"], "Studiengangcode mit Umlaut muss erhalten bleiben")
        assertEquals("Geologie", params["stdg1"])
    }

    @Test
    fun `url encoded umlaut survives roundtrip`() {
        val original = "BGÖK"
        val encoded = java.net.URLEncoder.encode(original, StandardCharsets.UTF_8)
        val decoded = URLDecoder.decode(encoded, StandardCharsets.UTF_8)
        assertEquals(original, decoded)
    }
}

// Lokale Kopie der Query-Parsing Logik für Testzwecke (vereinfacht)
private fun parseQueryParameters(href: String): Map<String, String> {
    val query = href.substringAfter('?', "")
    if (query.isBlank()) return emptyMap()
    return query.split('&')
        .mapNotNull { pair ->
            if (!pair.contains('=')) return@mapNotNull null
            val (k, v) = pair.split('=', limit = 2)
            k to v
        }
        .toMap()
}
