package app.sanctum.machina.build

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GitVersionParseTest {

    @Test
    fun tagged_clean() {
        assertEquals("v0.3.5-diagnostics", gitVersionParse("v0.3.5-diagnostics\n", 0))
    }

    @Test
    fun tagged_with_commits() {
        assertEquals(
            "v0.3.5-diagnostics-3-gabc1234",
            gitVersionParse("v0.3.5-diagnostics-3-gabc1234\n", 0),
        )
    }

    @Test
    fun tagged_dirty() {
        assertEquals(
            "v0.3.5-diagnostics-dev",
            gitVersionParse("v0.3.5-diagnostics-dev\n", 0),
        )
    }

    @Test
    fun empty_stdout_returns_null() {
        assertNull(gitVersionParse("", 0))
    }

    @Test
    fun nonzero_exit_returns_null() {
        assertNull(gitVersionParse("anything", 1))
    }

    @Test
    fun git_error_code_returns_null() {
        assertNull(gitVersionParse("anything", 128))
    }
}
