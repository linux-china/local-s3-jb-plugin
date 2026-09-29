package com.github.linuxchina.locals3jbplugin.project

import com.robothy.s3.core.exception.BucketNotExistException
import com.robothy.s3.core.exception.ObjectNotExistException
import com.robothy.s3.core.exception.VersionedObjectNotExistException
import com.robothy.s3.core.model.request.GetObjectOptions
import com.robothy.s3.core.model.request.PutObjectOptions
import com.robothy.s3.core.service.manager.LocalS3Manager
import com.robothy.s3.core.util.S3ObjectUtils
import com.robothy.s3.rest.service.BucketNameValidator
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.invariantSeparatorsPathString
import kotlin.io.path.name

/**
 * Creates the buckets of a [LocalS3ProjectConfig] and imports its seed data through the services of [manager].
 *
 * It can be applied each time the project is opened: existing buckets are kept, and existing objects are overwritten
 * with the content of their seed files, unless they already hold it.
 *
 * @param projectDirectory the directory that relative local paths are resolved against.
 */
class LocalS3ProjectSeeder(private val manager: LocalS3Manager, private val projectDirectory: Path) {

    data class Result(
        val createdBuckets: List<String>,
        val importedObjects: Int,
        /**
         * The directories that hold more than [MAX_FILES_PER_DIRECTORY] files, of which only the first ones were
         * imported.
         */
        val truncatedDirectories: List<Path>,
        val errors: List<String>,
    ) {
        val hasChanges: Boolean
            get() = createdBuckets.isNotEmpty() || importedObjects > 0
    }

    fun apply(config: LocalS3ProjectConfig): Result {
        val createdBuckets = mutableListOf<String>()
        val truncatedDirectories = mutableListOf<Path>()
        val errors = mutableListOf<String>()
        var importedObjects = 0

        val availableBuckets = config.allBuckets.filter { bucket ->
            runCatching { if (createBucketIfAbsent(bucket)) createdBuckets += bucket }
                .onFailure { errors += "Failed to create bucket $bucket: ${it.message ?: it.javaClass.simpleName}" }
                .isSuccess
        }.toSet()

        for ((bucket, objects) in config.seed) {
            if (bucket !in availableBuckets) {
                continue
            }
            for ((objectPath, localPath) in objects) {
                val source = resolve(localPath)
                val files = when {
                    Files.isRegularFile(source) -> listOf(source to fileKey(objectPath, source))
                    Files.isDirectory(source) -> {
                        val found = listFiles(source)
                        if (found.size > MAX_FILES_PER_DIRECTORY) {
                            truncatedDirectories.add(source)
                        }
                        found.take(MAX_FILES_PER_DIRECTORY).map { it to directoryKey(objectPath, source, it) }
                    }

                    else -> {
                        errors.add("Seed path $localPath of $bucket/$objectPath doesn't exist")
                        emptyList()
                    }
                }
                for ((file, key) in files) {
                    runCatching { if (putObjectIfChanged(bucket, key, file)) importedObjects++ }
                        .onFailure { errors += "Failed to import $file to $bucket/$key: ${it.message ?: it.javaClass.simpleName}" }
                }
            }
        }
        return Result(createdBuckets, importedObjects, truncatedDirectories, errors)
    }

    /**
     * @return `true` if the bucket was created, `false` if it already existed.
     */
    private fun createBucketIfAbsent(bucket: String): Boolean {
        val bucketService = manager.bucketService()
        try {
            bucketService.getBucket(bucket)
            return false
        } catch (_: BucketNotExistException) {
            // The services accept any name: validate it like Local S3 does for the buckets it creates.
            BucketNameValidator().validate(bucket)
            bucketService.createBucket(bucket)
            return true
        }
    }

    /**
     * Puts the file as the object, overwriting it, so that changes made to the seed files are imported.
     *
     * @return `true` if the object was imported, `false` if it already held the content of the file.
     */
    private fun putObjectIfChanged(bucket: String, key: String, file: Path): Boolean {
        val objectService = manager.objectService()
        val etag = objectEtag(bucket, key)
        if (etag != null && etag == Files.newInputStream(file).use(S3ObjectUtils::etag)) {
            return false
        }
        // Stream the content rather than pass the file: the storage may take a file over, i.e. move it.
        Files.newInputStream(file).use { content ->
            objectService.putObject(
                bucket, key, PutObjectOptions.builder()
                    .contentType(Files.probeContentType(file) ?: "application/octet-stream")
                    .size(Files.size(file))
                    .content(content)
                    .build()
            )
        }
        return true
    }

    /**
     * @return the ETag of the object, or `null` if it doesn't exist.
     */
    private fun objectEtag(bucket: String, key: String): String? = try {
        manager.objectService().headObject(bucket, key, GetObjectOptions.builder().build()).etag
    } catch (_: ObjectNotExistException) {
        null
    } catch (_: VersionedObjectNotExistException) {
        null
    }

    private fun resolve(localPath: String): Path {
        val path = when {
            localPath == "~" -> Path.of(System.getProperty("user.home"))
            localPath.startsWith("~/") -> Path.of(System.getProperty("user.home"), localPath.substring(2))
            else -> Path.of(localPath)
        }
        return projectDirectory.resolve(path).normalize()
    }

    companion object {
        /**
         * The maximum number of files imported from a directory, so that a wrong path doesn't import a whole tree.
         */
        const val MAX_FILES_PER_DIRECTORY = 200

        /**
         * The files under [directory], recursively and in a stable order, without hidden files such as `.DS_Store`,
         * and at most one more than [MAX_FILES_PER_DIRECTORY], to tell that there are too many.
         */
        private fun listFiles(directory: Path): List<Path> =
            Files.walk(directory).use { paths ->
                paths.filter { Files.isRegularFile(it) && !directory.relativize(it).any { part -> part.name.startsWith(".") } }
                    .sorted()
                    .limit(MAX_FILES_PER_DIRECTORY + 1L)
                    .toList()
            }

        /**
         * The key of a file seeded directly: the object path, or the file name under it if it ends with `/` or is
         * empty.
         */
        internal fun fileKey(objectPath: String, file: Path): String {
            val key = objectPath.trimStart('/')
            return if (key.isEmpty() || key.endsWith('/')) key + file.name else key
        }

        /**
         * The key of a file of a seeded directory: its path relative to the directory, under the object path.
         */
        internal fun directoryKey(objectPath: String, directory: Path, file: Path): String {
            val prefix = objectPath.trim('/')
            val relative = directory.relativize(file).invariantSeparatorsPathString
            return if (prefix.isEmpty()) relative else "$prefix/$relative"
        }
    }
}
