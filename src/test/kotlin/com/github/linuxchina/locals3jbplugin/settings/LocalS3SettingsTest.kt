package com.github.linuxchina.locals3jbplugin.settings

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.nio.file.Files
import java.nio.file.Path

class LocalS3SettingsTest : BasePlatformTestCase() {
    fun testExpectedDefaults() {
        val settings = LocalS3Settings()

        assertEquals("127.0.0.1", settings.host)
        assertEquals(29090, settings.port)
        assertFalse(settings.tlsEnabled)
        assertEquals("admin", settings.accessKeyId)
        assertEquals("admin", settings.secretAccessKey)
        assertEquals("http://127.0.0.1:29090", settings.endpoint)
        assertEquals(
            Path.of(System.getProperty("user.home"), ".locals3", "IU"),
            Path.of(settings.dataPath)
        )
    }

    fun testNormalizesBucketNamesAndCreatesTlsEndpoint() {
        val settings = LocalS3Settings().apply {
            host = "127.0.0.1"
            port = 9443
            tlsEnabled = true
            buckets = " first, second, first, ,third "
        }

        assertEquals("https://127.0.0.1:9443", settings.endpoint)
        assertEquals(listOf("first", "second", "third"), settings.bucketNames)
    }

    fun testMapsListenHostToClientHost() {
        val settings = LocalS3Settings().apply { port = 29090 }

        for (wildcard in listOf("0.0.0.0", "::", "[::]")) {
            settings.host = wildcard
            assertEquals("http://127.0.0.1:29090", settings.endpoint)
        }
        settings.host = "192.168.1.10"
        assertEquals("http://192.168.1.10:29090", settings.endpoint)
        settings.host = "::1"
        assertEquals("http://[::1]:29090", settings.endpoint)
        settings.host = "[::1]"
        assertEquals("http://[::1]:29090", settings.endpoint)
    }

    fun testCreatesDataDirectory() {
        val temporaryRoot = Files.createTempDirectory("local-s3-settings-test")
        val dataDirectory = temporaryRoot.resolve("nested/data")
        try {
            val settings = LocalS3Settings().apply {
                dataPath = dataDirectory.toString()
            }

            assertEquals(dataDirectory, settings.ensureDataDirectory())
            assertTrue(Files.isDirectory(dataDirectory))
        } finally {
            Files.deleteIfExists(dataDirectory)
            Files.deleteIfExists(dataDirectory.parent)
            Files.deleteIfExists(temporaryRoot)
        }
    }
}
