package app.belle.assistant.core

import org.junit.Assert.assertEquals
import org.junit.Test

class MiniJsonTest {
    @Test fun parsesObjectsArraysNumbersStrings() {
        @Suppress("UNCHECKED_CAST")
        val m = MiniJson.parse("""{"a": [1, 2.5, -3e2], "b": "x\"y\u00e9\n", "c": true, "d": null, "e": {}}""") as Map<String, Any?>
        assertEquals(listOf(1.0, 2.5, -300.0), m["a"])
        assertEquals("x\"yé\n", m["b"])
        assertEquals(true, m["c"]); assertEquals(null, m["d"]); assertEquals(emptyMap<String, Any?>(), m["e"])
    }
}
