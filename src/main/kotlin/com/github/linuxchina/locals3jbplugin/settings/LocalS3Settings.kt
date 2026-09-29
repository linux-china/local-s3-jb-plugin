package com.github.linuxchina.locals3jbplugin.settings

import com.intellij.openapi.application.ApplicationInfo
import com.intellij.openapi.components.*
import com.intellij.openapi.util.io.FileUtil
import java.nio.file.Files
import java.nio.file.Path

@Service(Service.Level.APP)
@State(name = "LocalS3Settings", storages = [Storage("LocalS3.xml", roamingType = RoamingType.DISABLED)])
class LocalS3Settings : PersistentStateComponent<LocalS3Settings.SettingsState> {
    private var settingsState = SettingsState()

    var host: String
        get() = settingsState.host
        set(value) {
            settingsState.host = value.trim()
        }

    var port: Int
        get() = settingsState.port
        set(value) {
            settingsState.port = value
        }

    var tlsEnabled: Boolean
        get() = settingsState.tlsEnabled
        set(value) {
            settingsState.tlsEnabled = value
        }

    /**
     * Whether Local S3 is started when a project is opened. Not part of [serverConfig]: it doesn't affect the server.
     */
    var autoStart: Boolean
        get() = settingsState.autoStart
        set(value) {
            settingsState.autoStart = value
        }

    var accessKeyId: String
        get() = settingsState.accessKeyId
        set(value) {
            settingsState.accessKeyId = value
        }

    var secretAccessKey: String
        get() = settingsState.secretAccessKey
        set(value) {
            settingsState.secretAccessKey = value
        }

    var buckets: String
        get() = settingsState.buckets
        set(value) {
            settingsState.buckets = value
        }

    var dataPath: String
        get() = settingsState.dataPath
        set(value) {
            settingsState.dataPath = value.trim()
        }

    /**
     * The address the server binds to: `localhost` is resolved to the loopback address.
     */
    val bindHost: String
        get() = bindHostOf(host)

    val clientHost: String
        get() = clientHostOf(host)

    val endpoint: String
        get() = endpointOf(host, port, tlsEnabled)

    val httpEndpoint: String
        get() = endpointOf(host, port, false)

    val bucketNames: List<String>
        get() = buckets.split(',')
            .map(String::trim)
            .filter(String::isNotEmpty)
            .distinct()

    /**
     * The settings that the running server depends on: a change of any of them requires a restart.
     */
    fun serverConfig() = ServerConfig(host, port, tlsEnabled, dataPath, accessKeyId, secretAccessKey, bucketNames)

    fun ensureDataDirectory(): Path = Path.of(FileUtil.expandUserHome(dataPath))
        .toAbsolutePath()
        .normalize()
        .also(Files::createDirectories)

    override fun getState(): SettingsState = settingsState

    override fun loadState(state: SettingsState) {
        settingsState = state
    }

    class SettingsState {
        var host: String = DEFAULT_HOST
        var port: Int = DEFAULT_PORT
        var tlsEnabled: Boolean = false
        var autoStart: Boolean = true
        var accessKeyId: String = DEFAULT_ACCESS_KEY_ID
        var secretAccessKey: String = DEFAULT_SECRET_ACCESS_KEY
        var buckets: String = DEFAULT_BUCKETS
        var dataPath: String = DEFAULT_DATA_PATH
    }

    data class ServerConfig(
        val host: String,
        val port: Int,
        val tlsEnabled: Boolean,
        val dataPath: String,
        val accessKeyId: String,
        val secretAccessKey: String,
        val bucketNames: List<String>,
    )

    companion object {
        const val DEFAULT_HOST = "127.0.0.1"
        val DEFAULT_PORT: Int by lazy {
            getProductListenPort()
        }
        const val DEFAULT_ACCESS_KEY_ID = "admin"
        const val DEFAULT_SECRET_ACCESS_KEY = "admin"
        const val DEFAULT_BUCKETS = "demo-bucket"

        /** Local S3 accepts any region; clients still need one to sign their requests. */
        const val REGION = "us-east-1"
        val DEFAULT_DATA_PATH: String by lazy {
            Path.of(System.getProperty("user.home"), ".locals3", currentProductCode()).toString()
        }

        fun getInstance(): LocalS3Settings = service()

        fun bindHostOf(host: String): String = host.trim().let {
            if (it.equals("localhost", ignoreCase = true)) DEFAULT_HOST else it
        }

        /**
         * The host that clients use to reach the server: the loopback address when listening on all interfaces.
         */
        fun clientHostOf(host: String): String = when (val trimmed = host.trim()) {
            "0.0.0.0", "::", "[::]", "" -> DEFAULT_HOST
            else -> if (trimmed.contains(':') && !trimmed.startsWith("[")) "[$trimmed]" else trimmed
        }

        fun endpointOf(host: String, port: Int, tlsEnabled: Boolean): String =
            "${if (tlsEnabled) "https" else "http"}://${clientHostOf(host)}:$port"

        private fun currentProductCode(): String {
            return ApplicationInfo.getInstance().build.productCode.ifEmpty { "IU" }
        }

        private fun getProductListenPort(): Int {
            return when (currentProductCode()) {
                "IU", "IC" -> 29090
                "CL" -> 29091
                "DB" -> 29092
                "GO" -> 29093
                "PY", "PC" -> 29094
                "PS" -> 29095
                "RM" -> 29096
                "RR" -> 29097
                "WS" -> 29098
                else -> 29099
            }
        }
    }
}
