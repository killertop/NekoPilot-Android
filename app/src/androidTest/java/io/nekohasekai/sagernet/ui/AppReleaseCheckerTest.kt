package io.nekohasekai.sagernet.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AppReleaseCheckerTest {

    @Test
    fun parsesOfficialGitHubReleaseMetadataThroughGoCore() {
        val release = AppReleaseChecker.parseRelease(
            """
            {
              "tag_name": "v1.5.2",
              "body": "Fix connection stability\n\n- Improve DNS fallback",
              "html_url": "https://github.com/killertop/NekoPilot-Android/releases/tag/v1.5.2"
            }
            """.trimIndent(),
        )

        assertEquals("1.5.2", release.version)
        assertEquals("Fix connection stability\n\n- Improve DNS fallback", release.notes)
        assertEquals(
            "https://github.com/killertop/NekoPilot-Android/releases/tag/v1.5.2",
            release.downloadPageUrl,
        )
    }

    @Test
    fun rejectsNonGitHubDownloadPages() {
        assertThrows(Exception::class.java) {
            AppReleaseChecker.parseRelease(
                """{"tag_name":"v1.5.2","html_url":"https://example.com/release"}""",
            )
        }
    }

    @Test
    fun comparesReleaseVersionsNumericallyThroughGoCore() {
        assertTrue(isRemoteVersionNewer("v1.10.0", "1.9.9"))
        assertTrue(isRemoteVersionNewer("1.5.2", "1.5.1"))
        assertTrue(isRemoteVersionNewer("1.5.1", "1.5.1-qa"))
        assertTrue(isRemoteVersionNewer("1.5.2", "1.5.2-beta"))
        assertFalse(isRemoteVersionNewer("1.5.1", "1.5.1"))
        assertFalse(isRemoteVersionNewer("1.5.2", "1.5.2+5"))
        assertFalse(isRemoteVersionNewer("1.5.2+9", "1.5.2+5"))
        assertFalse(isRemoteVersionNewer("1.5.0", "1.5.1"))
        assertFalse(isRemoteVersionNewer("not-a-version", "1.5.1"))
        assertFalse(isRemoteVersionNewer("999999999999999999999.1.0", "1.5.1"))
    }
}
