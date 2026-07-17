package moe.matsuri.nb4a.plugin

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PluginsTest {

    private val trusted = setOf("abcdef")

    @Test
    fun acceptsOnlyExpectedPackageWithTrustedSigner() {
        assertTrue(
            Plugins.isTrustedPluginIdentity(
                "moe.matsuri.exe.mieru",
                trusted,
                "moe.matsuri.exe.mieru",
                setOf("ABCDEF"),
            )
        )
        assertFalse(
            Plugins.isTrustedPluginIdentity(
                "moe.matsuri.exe.mieru",
                trusted,
                "attacker.plugin",
                setOf("abcdef"),
            )
        )
        assertFalse(
            Plugins.isTrustedPluginIdentity(
                "moe.matsuri.exe.mieru",
                trusted,
                "moe.matsuri.exe.mieru",
                setOf("untrusted"),
            )
        )
        assertFalse(
            Plugins.isTrustedPluginIdentity(
                "moe.matsuri.exe.mieru",
                emptySet(),
                "moe.matsuri.exe.mieru",
                setOf("abcdef"),
            )
        )
    }
}
