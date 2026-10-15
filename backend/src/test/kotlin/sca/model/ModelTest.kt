package sca.model

import org.junit.jupiter.api.Test
import tools.jackson.module.kotlin.readValue
import java.time.Instant
import kotlin.test.assertEquals

class ModelTest {
    @Test fun `ScanResult round-trips through jackson`() {
        val c = Component(key = "npm/lodash@4.17.15", purl = "pkg:npm/lodash@4.17.15", name = "lodash",
            version = "4.17.15", ecosystem = "npm", licenses = Sourced("MIT", "cdxgen"), foundBy = setOf("cdxgen", "syft"))
        val r = ScanResult(TargetInfo(TargetKind.REPO, "https://github.com/x/y", null, "abc"), Status.DONE, emptyList(),
            listOf(c), setOf("a" to "b"), setOf("a"), emptyList(), Instant.parse("2026-10-15T00:00:00Z"))
        val json = Json.mapper.writeValueAsString(r)
        assertEquals(r, Json.mapper.readValue<ScanResult>(json))
    }
}
