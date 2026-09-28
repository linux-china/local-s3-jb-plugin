package com.github.linuxchina.locals3jbplugin.services

import com.github.linuxchina.locals3jbplugin.LocalS3Bundle
import com.github.linuxchina.locals3jbplugin.settings.LocalS3Configurable
import com.github.linuxchina.locals3jbplugin.settings.LocalS3Settings
import com.github.linuxchina.locals3jbplugin.settings.LocalS3SettingsListener
import com.intellij.notification.Notification
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.robothy.s3.rest.LocalS3
import com.robothy.s3.rest.bootstrap.LocalS3Mode
import java.net.BindException
import java.net.HttpURLConnection
import java.net.Proxy
import java.net.URI
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

@Service(Service.Level.APP)
class LocalS3Service : Disposable {
    private var localS3: LocalS3? = null

    /**
     * Endpoint of a Local S3 started by another process (e.g. another IDE) that is reused instead of starting our own.
     */
    private var sharedEndpoint: String? = null

    @Synchronized
    fun isRunning(): Boolean {
        return localS3?.isRunning ?: false
    }

    @Synchronized
    fun isShared(): Boolean = sharedEndpoint != null

    /**
     * Starts Local S3, or reuses the one already listening on the configured port. Failures are reported with a
     * notification instead of being thrown, so they don't end up in the IDE error reporter.
     *
     * @return `true` if Local S3 is available afterward, either started here or shared with another process.
     */
    @Synchronized
    fun start(project: Project? = null): Boolean {
        if (isRunning()) {
            return true
        }
        sharedEndpoint = null
        val settings = LocalS3Settings.getInstance()
        if (reuseRunningLocalS3(settings, project)) {
            return true
        }
        try {
            localS3 = buildLocalS3(settings).also(LocalS3::start)
            return true
        } catch (e: Exception) {
            closeQuietly()
            // Another IDE may have bound the port between the probe and our start.
            if (reuseRunningLocalS3(settings, project)) {
                return true
            }
            thisLogger().warn("Failed to start Local S3 at ${settings.endpoint}", e)
            notifyStartFailure(settings, e, project)
            return false
        }
    }

    @Synchronized
    fun restart(project: Project? = null): Boolean {
        stop()
        return start(project)
    }

    /**
     * Restarts Local S3 off the EDT with a background progress, e.g. after the settings are applied, and notifies
     * [LocalS3SettingsListener]s only if it is available afterward, so that integrations such as Big Data Tools
     * don't sync to an endpoint that isn't up.
     */
    fun restartInBackground(project: Project? = null) {
        object : Task.Backgroundable(project, LocalS3Bundle["progress.restart.title"], false) {
            override fun run(indicator: ProgressIndicator) {
                if (restart(project)) {
                    publishSettingsChanged()
                }
            }
        }.queue()
    }

    @Synchronized
    fun stop() {
        sharedEndpoint = null
        localS3?.let {
            if (it.isRunning) {
                it.close()
            }
            localS3 = null
        }
    }

    override fun dispose() {
        stop()
    }

    private fun buildLocalS3(settings: LocalS3Settings): LocalS3 {
        val dataDirectory = settings.ensureDataDirectory()
        val builder = LocalS3.builder()
            .bindHost(settings.host)
            .port(settings.port)
            .mode(LocalS3Mode.PERSISTENCE)
            .dataPath(dataDirectory.toString())
            .credentials(settings.accessKeyId, settings.secretAccessKey)
            .buckets(*settings.bucketNames.toTypedArray())
            .netty {
                it.daemonThreads(true)
            }

        if (settings.tlsEnabled) {
            builder.tls {
                it.selfSigned().required(true)
            }
        }
        return builder.build()
    }

    private fun closeQuietly() {
        try {
            localS3?.close()
        } catch (e: Exception) {
            thisLogger().debug("Failed to close Local S3 after a failed start", e)
        }
        localS3 = null
    }

    private fun reuseRunningLocalS3(settings: LocalS3Settings, project: Project?): Boolean {
        if (!isLocalS3Listening(settings)) {
            return false
        }
        sharedEndpoint = settings.endpoint
        thisLogger().info("Reusing Local S3 already running at ${settings.endpoint}")
        notify(
            project,
            NotificationType.INFORMATION,
            LocalS3Bundle["notification.shared.title"],
            LocalS3Bundle["notification.shared.content", settings.endpoint],
        )
        return true
    }

    private fun notifyStartFailure(settings: LocalS3Settings, error: Exception, project: Project?) {
        val content = when {
            error.causes().any { it is BindException || it.message.orEmpty().contains("Address already in use", true) } ->
                LocalS3Bundle["notification.startFailed.portInUse", settings.port]

            error.causes().any { it.message.orEmpty().contains("locked", true) } ->
                LocalS3Bundle["notification.startFailed.dataLocked", settings.dataPath]

            else -> LocalS3Bundle["notification.startFailed.other", error.causes().last().message ?: error.javaClass.simpleName]
        }
        notify(project, NotificationType.ERROR, LocalS3Bundle["notification.startFailed.title", settings.endpoint], content) {
            addAction(NotificationAction.createSimpleExpiring(LocalS3Bundle["notification.action.openSettings"]) {
                ShowSettingsUtil.getInstance().showSettingsDialog(project, LocalS3Configurable::class.java)
            })
            addAction(NotificationAction.createSimpleExpiring(LocalS3Bundle["notification.action.retry"]) {
                ApplicationManager.getApplication().executeOnPooledThread {
                    // The failed start may follow a settings change that integrations haven't been told about.
                    if (start(project)) {
                        publishSettingsChanged()
                    }
                }
            })
        }
    }

    private fun publishSettingsChanged() {
        ApplicationManager.getApplication().messageBus
            .syncPublisher(LocalS3SettingsListener.TOPIC)
            .settingsChanged()
    }

    private fun notify(
        project: Project?,
        type: NotificationType,
        title: String,
        content: String,
        configure: Notification.() -> Unit = {},
    ) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup(NOTIFICATION_GROUP_ID)
            .createNotification(title, content, type)
            .apply(configure)
            .notify(project)
    }

    private fun Throwable.causes(): List<Throwable> = generateSequence(this) { it.cause.takeIf { cause -> cause !== it } }.toList()

    companion object {
        const val NOTIFICATION_GROUP_ID = "Local S3"
        private const val HEALTH_CHECK_PATH = "/_health"
        private const val CONNECT_TIMEOUT_MILLIS = 500
        private const val READ_TIMEOUT_MILLIS = 1000

        /**
         * Tells whether a Local S3 answers on the configured port, by its unauthenticated health check
         * `GET /_health`, which answers `{"status":"UP"}`.
         */
        internal fun isLocalS3Listening(settings: LocalS3Settings): Boolean {
            val host = when (settings.host) {
                "0.0.0.0", "::", "[::]" -> "127.0.0.1"
                else -> settings.host
            }
            val scheme = if (settings.tlsEnabled) "https" else "http"
            val authority = if (host.contains(':') && !host.startsWith("[")) "[$host]" else host
            return try {
                val connection = URI("$scheme://$authority:${settings.port}$HEALTH_CHECK_PATH").toURL()
                    .openConnection(Proxy.NO_PROXY) as HttpURLConnection
                if (connection is HttpsURLConnection) {
                    // Local S3 uses a self-signed certificate.
                    connection.sslSocketFactory = trustAllSslContext.socketFactory
                    connection.setHostnameVerifier { _, _ -> true }
                }
                connection.connectTimeout = CONNECT_TIMEOUT_MILLIS
                connection.readTimeout = READ_TIMEOUT_MILLIS
                try {
                    connection.responseCode == HttpURLConnection.HTTP_OK &&
                            connection.inputStream.bufferedReader().use { it.readText() }.contains("\"UP\"")
                } finally {
                    connection.disconnect()
                }
            } catch (_: Exception) {
                false
            }
        }

        private val trustAllSslContext: SSLContext by lazy {
            val trustAll = object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = Unit
                override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) = Unit
                override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            }
            SSLContext.getInstance("TLS").apply { init(null, arrayOf<TrustManager>(trustAll), SecureRandom()) }
        }
    }
}
