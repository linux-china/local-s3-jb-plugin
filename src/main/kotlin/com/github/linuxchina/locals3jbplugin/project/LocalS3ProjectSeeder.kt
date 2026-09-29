package com.github.linuxchina.locals3jbplugin.project

import com.intellij.openapi.progress.ProgressIndicator
import com.robothy.s3.core.exception.BucketNotExistException
import com.robothy.s3.core.exception.ObjectNotExistException
import com.robothy.s3.core.exception.VersionedObjectNotExistException
import com.robothy.s3.core.model.request.GetObjectOptions
import com.robothy.s3.core.model.request.PutObjectOptions
import com.robothy.s3.core.service.manager.LocalS3Manager
import com.robothy.s3.core.util.S3ObjectUtils
import com.robothy.s3.rest.service.BucketNameValidator
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import kotlin.io.path.extension
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

    /**
     * @param indicator shows the progress, and cancels the import between objects, e.g. when Local S3 is stopped.
     */
    fun apply(config: LocalS3ProjectConfig, indicator: ProgressIndicator? = null): Result {
        val createdBuckets = mutableListOf<String>()
        val truncatedDirectories = mutableListOf<Path>()
        val errors = mutableListOf<String>()
        var importedObjects = 0

        val availableBuckets = config.allBuckets.filter { bucket ->
            // Outside runCatching, which would swallow the cancellation.
            indicator?.checkCanceled()
            runCatching { if (createBucketIfAbsent(bucket)) createdBuckets += bucket }
                .onFailure { errors += "Failed to create bucket $bucket: ${it.message ?: it.javaClass.simpleName}" }
                .isSuccess
        }.toSet()

        // Listed first, so that the progress has a total.
        val imports = mutableListOf<Import>()
        for ((bucket, objects) in config.seed) {
            if (bucket !in availableBuckets) {
                continue
            }
            for ((objectPath, localPath) in objects) {
                indicator?.checkCanceled()
                val source = resolve(localPath)
                when {
                    Files.isRegularFile(source) -> imports += Import(bucket, fileKey(objectPath, source), source)
                    Files.isDirectory(source) -> {
                        val found = listFiles(source)
                        if (found.size > MAX_FILES_PER_DIRECTORY) {
                            truncatedDirectories.add(source)
                        }
                        found.take(MAX_FILES_PER_DIRECTORY)
                            .mapTo(imports) { Import(bucket, directoryKey(objectPath, source, it), it) }
                    }

                    else -> errors.add("Seed path $localPath of $bucket/$objectPath doesn't exist")
                }
            }
        }

        indicator?.isIndeterminate = imports.isEmpty()
        for ((index, import) in imports.withIndex()) {
            indicator?.checkCanceled()
            indicator?.text2 = "${import.bucket}/${import.key}"
            indicator?.fraction = index.toDouble() / imports.size
            runCatching { if (putObjectIfChanged(import.bucket, import.key, import.file)) importedObjects++ }
                .onFailure {
                    errors += "Failed to import ${import.file} to ${import.bucket}/${import.key}: ${it.message ?: it.javaClass.simpleName}"
                }
        }
        return Result(createdBuckets, importedObjects, truncatedDirectories, errors)
    }

    private data class Import(val bucket: String, val key: String, val file: Path)

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
                    .contentType(contentType(file))
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
        const val MAX_FILES_PER_DIRECTORY = 256

        private const val DEFAULT_CONTENT_TYPE = "application/octet-stream"

        /**
         * Content types of common extensions, looked up before [Files.probeContentType], which often returns `null` on
         * macOS and Linux, e.g. for `.csv` or `.json`, and whose results differ between platforms.
         */
        private val CONTENT_TYPES = mapOf(
            "html" to "text/html; charset=utf-8",
            "htm" to "text/html; charset=utf-8",
            "css" to "text/css; charset=utf-8",
            "js" to "text/javascript; charset=utf-8",
            "ts" to "text/typescript; charset=utf-8",
            "mjs" to "text/javascript; charset=utf-8",
            "json" to "application/json",
            "map" to "application/json",
            "xml" to "application/xml",
            "txt" to "text/plain; charset=utf-8",
            "log" to "text/plain; charset=utf-8",
            "ini" to "text/plain; charset=utf-8",
            "sql" to "application/sql",
            "toml" to "application/toml",
            "md" to "text/markdown; charset=utf-8",
            "csv" to "text/csv; charset=utf-8",
            "tsv" to "text/tab-separated-values; charset=utf-8",
            "yaml" to "application/yaml",
            "yml" to "application/yaml",
            "ndjson" to "application/x-ndjson",
            "jsonl" to "application/x-ndjson",
            "parquet" to "application/vnd.apache.parquet",
            "avro" to "application/avro",
            "orc" to "application/x-orc",
            "arrow" to "application/vnd.apache.arrow.file",
            "feather" to "application/vnd.apache.arrow.file",
            "pdf" to "application/pdf",
            "zip" to "application/zip",
            "gz" to "application/gzip",
            "tar" to "application/x-tar",
            "bz2" to "application/x-bzip2",
            "zst" to "application/zstd",
            "wasm" to "application/wasm",
            "png" to "image/png",
            "jpg" to "image/jpeg",
            "jpeg" to "image/jpeg",
            "gif" to "image/gif",
            "webp" to "image/webp",
            "avif" to "image/avif",
            "svg" to "image/svg+xml",
            "ico" to "image/x-icon",
            "woff" to "font/woff",
            "woff2" to "font/woff2",
            "ttf" to "font/ttf",
            "otf" to "font/otf",
            "mp3" to "audio/mpeg",
            "wav" to "audio/wav",
            "mp4" to "video/mp4",
            "mov" to "video/quicktime",
            "webm" to "video/webm",
            "xls" to "application/vnd.ms-excel",
            "xlsx" to "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            "doc" to "application/msword",
            "docx" to "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "ppt" to "application/vnd.ms-powerpoint",
            "pptx" to "application/vnd.openxmlformats-officedocument.presentationml.presentation",
        )

        /**
         * The content type of a seed file, from its extension, else as probed by the platform.
         */
        internal fun contentType(file: Path): String =
            CONTENT_TYPES[file.extension.lowercase()]
                ?: runCatching { Files.probeContentType(file) }.getOrNull()
                ?: DEFAULT_CONTENT_TYPE

        /**
         * The files under [directory], recursively and in a stable order, without hidden files such as `.DS_Store`,
         * and at most one more than [MAX_FILES_PER_DIRECTORY], to tell that there are too many.
         *
         * The walk stops as soon as that many files are found, and doesn't enter hidden directories such as `.git`,
         * so that a wrong path such as `~` doesn't walk a whole tree.
         */
        private fun listFiles(directory: Path): List<Path> {
            val files = mutableListOf<Path>()
            collectFiles(directory, files)
            return files
        }

        /**
         * Adds the files under [directory] to [files], each directory's entries sorted by name, until [files] holds
         * more than [MAX_FILES_PER_DIRECTORY] of them.
         */
        private fun collectFiles(directory: Path, files: MutableList<Path>) {
            val entries = Files.newDirectoryStream(directory).use { stream ->
                stream.filterNot { it.name.startsWith(".") }.sortedBy { it.name }
            }
            for (entry in entries) {
                if (files.size > MAX_FILES_PER_DIRECTORY) {
                    return
                }
                when {
                    // Like Files.walk, don't follow links to directories.
                    Files.isDirectory(entry, LinkOption.NOFOLLOW_LINKS) -> collectFiles(entry, files)
                    Files.isRegularFile(entry) -> files.add(entry)
                }
            }
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
