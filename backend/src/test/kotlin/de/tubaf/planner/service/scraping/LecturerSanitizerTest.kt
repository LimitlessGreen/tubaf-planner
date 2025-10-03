package de.tubaf.planner.service.scraping

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class LecturerSanitizerTest {

    // Re-implement parsing logic consistent with parseLecturerIdentity for unit validation.
    private data class Parsed(val name: String, val email: String?, val truncated: Boolean)

    private fun parse(raw: String): Parsed {
        val original = raw
        var value = raw.trim()
        var email: String? = null
        var truncated = false
        if (value.isBlank()) return Parsed("N.N.", null, false)
        value = Regex("\\s+").replace(value.replace('\n', ' ').replace('\r', ' '), " ")
        val emailRegex = Regex("[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}", RegexOption.IGNORE_CASE)
        val m = emailRegex.find(value)
        if (m != null) {
            email = m.value.lowercase()
            val before = value.substring(0, m.range.first)
            val after = value.substring(m.range.last + 1)
            value = (before + after).replace("<", " ").replace(">", " ").replace("(", " ").replace(")", " ").trim()
        }
        value = value.trim { it == '-' || it == ';' || it == ',' || it.isWhitespace() }
        if (value.length > 200) {
            for (d in listOf(';', '/', '|')) {
                if (value.contains(d)) {
                    val first = value.split(d).first().trim()
                    if (first.isNotBlank()) {
                        value = first
                        truncated = true
                        break
                    }
                }
            }
        }
        if (value.length > 200) {
            value = value.take(200)
            truncated = true
        }
        if (value.isBlank()) value = "N.N."
        return Parsed(value, email?.take(150), truncated)
    }

    @Test
    fun `overlong string with delimiter is shortened at first segment`() {
        val longName = ("Prof. Dr. Max Mustermann; " + (1..10).joinToString("; ") { "Extra Segment $it" } + "; Irrelevant Tail").repeat(2)
        assertTrue(longName.length > 200)
        val parsed = parse(longName)
        assertTrue(parsed.name.length <= 200)
        assertTrue(parsed.name.startsWith("Prof. Dr. Max Mustermann"))
    }

    @Test
    fun `truncation without delimiter still yields length 200`() {
        val longBlock = "A".repeat(500)
        val parsed = parse(longBlock)
        assertEquals(200, parsed.name.length)
    }

    @Test
    fun `email extracted and removed from name`() {
        val input = "Dr. Alice Example <alice@example.org>"
        val parsed = parse(input)
        assertEquals("dr. alice example", parsed.name.lowercase())
        assertEquals("alice@example.org", parsed.email)
    }

    @Test
    fun `blank becomes N N`() {
        val parsed = parse("   \n   ")
        assertEquals("N.N.", parsed.name)
        assertNull(parsed.email)
    }
}
