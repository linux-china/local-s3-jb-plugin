package com.github.linuxchina.locals3jbplugin.settings

import com.intellij.openapi.application.ApplicationInfo
import com.intellij.openapi.components.*
import java.nio.file.Files
import java.nio.file.Path

@Service(Service.Level.APP)
@State(name = "LocalS3Settings", storages = [Storage("LocalS3.xml")])
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

    val endpoint: String
        get() = "${if (tlsEnabled) "https" else "http"}://$host:$port"

    val bucketNames: List<String>
        get() = buckets.split(',')
            .map(String::trim)
            .filter(String::isNotEmpty)
            .distinct()

    fun ensureDataDirectory(): Path = Path.of(dataPath)
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
        var accessKeyId: String = DEFAULT_ACCESS_KEY_ID
        var secretAccessKey: String = DEFAULT_SECRET_ACCESS_KEY
        var buckets: String = DEFAULT_BUCKETS
        var dataPath: String = DEFAULT_DATA_PATH
    }

    companion object {
        const val DEFAULT_HOST = "127.0.0.1"
        val DEFAULT_PORT: Int by lazy {
            getProductListenPort()
        }
        const val DEFAULT_ACCESS_KEY_ID = "admin"
        const val DEFAULT_SECRET_ACCESS_KEY = "admin"
        const val DEFAULT_BUCKETS = "demo-bucket"
        val DEFAULT_DATA_PATH: String by lazy {
            Path.of(System.getProperty("user.home"), ".locals3", currentProductCode()).toString()
        }

        fun getInstance(): LocalS3Settings = service()

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
