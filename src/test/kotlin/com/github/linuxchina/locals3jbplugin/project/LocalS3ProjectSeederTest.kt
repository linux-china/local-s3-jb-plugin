package com.github.linuxchina.locals3jbplugin.project

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.io.delete
import com.robothy.s3.core.exception.ObjectNotExistException
import com.robothy.s3.core.model.request.GetObjectOptions
import com.robothy.s3.core.service.manager.LocalS3Manager
import java.nio.file.Files
import java.nio.file.Path

class LocalS3ProjectSeederTest : BasePlatformTestCase() {
    private lateinit var projectDirectory: Path
    private lateinit var manager: LocalS3Manager

    override fun setUp() {
        super.setUp()
        projectDirectory = Files.createTempDirectory("local-s3-seeder-test")
        manager = LocalS3Manager.createInMemoryS3Manager()
    }

    override fun tearDown() {
        try {
            manager.close()
            projectDirectory.delete()
        } finally {
            super.tearDown()
        }
    }

    fun testParsesConfig() {
        val config = LocalS3ProjectConfig.parse(
            """
            {
              "buckets": ["demo-bucket", " demo2-bucket ", "demo-bucket"],
              "seed": {
                "demo-bucket": { "avatars": "src/test/resources/s3-seed/avatars" },
                "seed-only-bucket": { "logo.png": "logo.png" }
              }
            }
            """.trimIndent()
        )

        assertEquals(listOf("demo-bucket", "demo2-bucket"), config.buckets)
        assertEquals(mapOf("avatars" to "src/test/resources/s3-seed/avatars"), config.seed["demo-bucket"])
        assertEquals(listOf("demo-bucket", "demo2-bucket", "seed-only-bucket"), config.allBuckets)
    }

    fun testRejectsInvalidConfig() {
        assertInvalidConfig("{")
        assertInvalidConfig("""{"buckets": "demo"}""")
        assertInvalidConfig("""{"seed": {"demo": {"avatars": 1}}}""")
    }

    // Not a lambda in the test: JUnit 3 would take the synthetic test*$lambda methods for tests.
    private fun assertInvalidConfig(text: String) {
        try {
            LocalS3ProjectConfig.parse(text)
            fail("Expected $text to be rejected")
        } catch (_: IllegalArgumentException) {
        }
    }

    fun testCreatesBucketsAndImportsFilesAndDirectories() {
        val avatars = Files.createDirectories(projectDirectory.resolve("seed/avatars"))
        Files.writeString(avatars.resolve("a.txt"), "a")
        Files.writeString(Files.createDirectories(avatars.resolve("nested")).resolve("b.txt"), "b")
        Files.writeString(avatars.resolve(".DS_Store"), "hidden")
        Files.writeString(projectDirectory.resolve("seed/readme.txt"), "readme")
        val config = LocalS3ProjectConfig(
            buckets = listOf("demo-bucket", "empty-bucket"),
            seed = mapOf(
                "demo-bucket" to mapOf(
                    "avatars" to "seed/avatars",
                    "docs/readme.txt" to "seed/readme.txt",
                    "files/" to "seed/readme.txt",
                ),
            ),
        )

        val result = LocalS3ProjectSeeder(manager, projectDirectory).apply(config)

        assertEquals(listOf("demo-bucket", "empty-bucket"), result.createdBuckets)
        assertEquals(4, result.importedObjects)
        assertEmpty(result.errors)
        assertEquals("a", read("demo-bucket", "avatars/a.txt"))
        assertEquals("b", read("demo-bucket", "avatars/nested/b.txt"))
        assertEquals("readme", read("demo-bucket", "docs/readme.txt"))
        assertEquals("readme", read("demo-bucket", "files/readme.txt"))
        assertFalse(exists("demo-bucket", "avatars/.DS_Store"))
        // The seed files are copied, not taken over by the storage.
        assertTrue(Files.exists(avatars.resolve("a.txt")))
    }

    fun testOverwritesChangedObjectsOnlyWhenAppliedAgain() {
        Files.writeString(projectDirectory.resolve("readme.txt"), "original")
        val config = LocalS3ProjectConfig(seed = mapOf("demo-bucket" to mapOf("readme.txt" to "readme.txt")))
        LocalS3ProjectSeeder(manager, projectDirectory).apply(config)

        val unchanged = LocalS3ProjectSeeder(manager, projectDirectory).apply(config)

        assertFalse(unchanged.hasChanges)

        Files.writeString(projectDirectory.resolve("readme.txt"), "changed")

        val changed = LocalS3ProjectSeeder(manager, projectDirectory).apply(config)

        assertEquals(1, changed.importedObjects)
        assertEquals("changed", read("demo-bucket", "readme.txt"))
    }

    fun testLimitsFilesImportedFromDirectory() {
        val directory = Files.createDirectories(projectDirectory.resolve("many"))
        repeat(LocalS3ProjectSeeder.MAX_FILES_PER_DIRECTORY + 5) {
            Files.writeString(directory.resolve("file-%03d.txt".format(it)), "$it")
        }
        val config = LocalS3ProjectConfig(seed = mapOf("demo-bucket" to mapOf("many" to "many")))

        val result = LocalS3ProjectSeeder(manager, projectDirectory).apply(config)

        assertEquals(LocalS3ProjectSeeder.MAX_FILES_PER_DIRECTORY, result.importedObjects)
        assertEquals(listOf(directory), result.truncatedDirectories)
    }

    fun testSkipsHiddenDirectoriesAndImportsFirstFilesInOrder() {
        val directory = Files.createDirectories(projectDirectory.resolve("tree"))
        // Files in a hidden directory don't count toward the limit.
        val git = Files.createDirectories(directory.resolve(".git/objects"))
        repeat(LocalS3ProjectSeeder.MAX_FILES_PER_DIRECTORY + 5) {
            Files.writeString(git.resolve("object-%03d".format(it)), "$it")
        }
        Files.writeString(Files.createDirectories(directory.resolve("b")).resolve("b.txt"), "b")
        repeat(LocalS3ProjectSeeder.MAX_FILES_PER_DIRECTORY) {
            Files.writeString(Files.createDirectories(directory.resolve("a")).resolve("a-%03d.txt".format(it)), "$it")
        }
        Files.writeString(directory.resolve("c.txt"), "c")
        val config = LocalS3ProjectConfig(seed = mapOf("demo-bucket" to mapOf("tree" to "tree")))

        val result = LocalS3ProjectSeeder(manager, projectDirectory).apply(config)

        assertEquals(LocalS3ProjectSeeder.MAX_FILES_PER_DIRECTORY, result.importedObjects)
        assertEquals(listOf(directory), result.truncatedDirectories)
        assertTrue(exists("demo-bucket", "tree/a/a-255.txt"))
        assertFalse(exists("demo-bucket", "tree/b/b.txt"))
        assertFalse(exists("demo-bucket", "tree/.git/objects/object-000"))
    }

    fun testReportsMissingPathsAndInvalidBuckets() {
        val config = LocalS3ProjectConfig(
            buckets = listOf("Invalid_Bucket"),
            seed = mapOf("demo-bucket" to mapOf("missing" to "does/not/exist")),
        )

        val result = LocalS3ProjectSeeder(manager, projectDirectory).apply(config)

        assertEquals(listOf("demo-bucket"), result.createdBuckets)
        assertEquals(2, result.errors.size)
    }

    fun testContentTypeFromExtension() {
        assertEquals("text/csv; charset=utf-8", LocalS3ProjectSeeder.contentType(Path.of("data/users.CSV")))
        assertEquals("application/json", LocalS3ProjectSeeder.contentType(Path.of("config.json")))
        assertEquals("application/vnd.apache.parquet", LocalS3ProjectSeeder.contentType(Path.of("t.parquet")))
        assertEquals("image/svg+xml", LocalS3ProjectSeeder.contentType(Path.of("logo.svg")))
        assertEquals(
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            LocalS3ProjectSeeder.contentType(Path.of("report.xlsx")),
        )
        assertEquals("application/octet-stream", LocalS3ProjectSeeder.contentType(Path.of("no-extension")))
    }

    private fun exists(bucket: String, key: String): Boolean = try {
        manager.objectService().headObject(bucket, key, GetObjectOptions.builder().build())
        true
    } catch (_: ObjectNotExistException) {
        false
    }

    private fun read(bucket: String, key: String): String =
        manager.objectService().getObject(bucket, key, GetObjectOptions.builder().build())
            .content.use { String(it.readAllBytes()) }
}
