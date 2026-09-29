package com.github.linuxchina.locals3jbplugin.project

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParseException
import com.google.gson.JsonParser
import java.nio.file.Files
import java.nio.file.Path

/**
 * The buckets and seed data a project needs, read from [FILE_NAME] at the project root, which is meant to be committed
 * to Git:
 *
 * ```json
 * {
 *   "buckets": ["demo-bucket"],
 *   "seed": {
 *     "demo-bucket": {
 *       "avatars": "src/test/resources/s3-seed/avatars"
 *     }
 *   }
 * }
 * ```
 *
 * @property buckets the buckets to create.
 * @property seed the local files or directories to import, by object path, by bucket.
 */
data class LocalS3ProjectConfig(
    val buckets: List<String> = emptyList(),
    val seed: Map<String, Map<String, String>> = emptyMap(),
) {
    /**
     * The buckets to create, including the ones only named by [seed].
     */
    val allBuckets: List<String>
        get() = (buckets + seed.keys).distinct()

    companion object {
        const val FILE_NAME = ".locals3.json"

        /**
         * @return the configuration of the project at [projectDirectory], or `null` if it has none.
         * @throws IllegalArgumentException if the file isn't a valid configuration.
         */
        fun load(projectDirectory: Path): LocalS3ProjectConfig? {
            val file = projectDirectory.resolve(FILE_NAME)
            if (!Files.isRegularFile(file)) {
                return null
            }
            return parse(Files.readString(file))
        }

        /**
         * @throws IllegalArgumentException if [text] isn't a valid configuration.
         */
        fun parse(text: String): LocalS3ProjectConfig {
            val root = try {
                JsonParser.parseString(text)
            } catch (e: JsonParseException) {
                throw IllegalArgumentException("Invalid JSON: ${e.cause?.message ?: e.message}", e)
            }
            if (root.isJsonNull) {
                return LocalS3ProjectConfig()
            }
            require(root.isJsonObject) { "The configuration must be a JSON object" }
            val config = root.asJsonObject
            val buckets = config.get("buckets").orNull()?.let { element ->
                require(element.isJsonArray) { "\"buckets\" must be an array of bucket names" }
                element.asJsonArray.map { it.asStringOrFail("\"buckets\" must be an array of bucket names").trim() }
                    .filter(String::isNotEmpty)
            }.orEmpty()
            val seed = config.get("seed").orNull()?.let { element ->
                require(element.isJsonObject) { "\"seed\" must map bucket names to objects" }
                element.asJsonObject.entrySet().associateTo(LinkedHashMap()) { (name, objects) ->
                    val bucket = name.trim()
                    bucket to parseSeedObjects(bucket, objects)
                }
            }.orEmpty()
            return LocalS3ProjectConfig(buckets.distinct(), seed)
        }

        private fun parseSeedObjects(bucket: String, element: JsonElement): Map<String, String> {
            val message = "\"seed\".\"$bucket\" must map object paths to local paths"
            require(element.isJsonObject) { message }
            return (element as JsonObject).entrySet().associateTo(LinkedHashMap()) { (objectPath, localPath) ->
                objectPath to localPath.asStringOrFail(message)
            }
        }

        private fun JsonElement?.orNull(): JsonElement? = this?.takeUnless { it.isJsonNull }

        private fun JsonElement.asStringOrFail(message: String): String {
            require(isJsonPrimitive && asJsonPrimitive.isString) { message }
            return asString
        }
    }
}
