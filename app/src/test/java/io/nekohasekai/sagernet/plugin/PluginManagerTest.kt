package io.nekohasekai.sagernet.plugin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.nio.file.Files

class PluginManagerTest {

    @Test
    fun executableMustRemainInsideNativeLibraryDirectory() {
        val parent = Files.createTempDirectory("plugin-test").toFile()
        try {
            val root = parent.resolve("lib").apply { mkdirs() }
            val executable = root.resolve("libplugin.so").apply {
                writeText("test")
                check(setExecutable(true))
            }
            val outside = parent.resolve("outside").apply {
                writeText("test")
                check(setExecutable(true))
            }

            assertEquals(
                executable.canonicalFile,
                PluginManager.resolvePluginExecutable(root, "libplugin.so"),
            )
            assertThrows(IllegalArgumentException::class.java) {
                PluginManager.resolvePluginExecutable(root, "../outside")
            }
            assertThrows(IllegalArgumentException::class.java) {
                PluginManager.resolvePluginExecutable(root, outside.absolutePath)
            }
        } finally {
            parent.deleteRecursively()
        }
    }
}
