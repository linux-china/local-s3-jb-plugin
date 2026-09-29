package com.github.linuxchina.locals3jbplugin.services

import com.github.linuxchina.locals3jbplugin.LocalS3Bundle
import com.github.linuxchina.locals3jbplugin.settings.LocalS3Configurable
import com.github.linuxchina.locals3jbplugin.settings.LocalS3Settings
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
import com.intellij.util.concurrency.AppExecutorUtil
import com.robothy.s3.core.service.manager.LocalS3Manager
import com.robothy.s3.rest.LocalS3
import com.robothy.s3.rest.bootstrap.LocalS3Mode
import java.net.BindException
import java.net.HttpURLConnection
import java.net.Proxy
import java.net.URI
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
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

    /**
     * Read without locking, so that the status bar and actions don't wait for a start probing the port.
     */
    @Volatile
    private var status: LocalS3Status = LocalS3Status.STOPPED

    /**
     * Periodic check scheduled while Local S3 is available, see [checkHealth].
     */
    private var healthCheck: ScheduledFuture<*>? = null

    /**
     * Set when the user stops Local S3, so that opening another project doesn't start it again in this IDE session.
     */
    @Volatile
    private var stoppedByUser = false

    fun status(): LocalS3Status = status

    @Synchronized
    fun isRunning(): Boolean {
        return localS3?.isRunning ?: false
    }

    @Synchronized
    fun isShared(): Boolean = sharedEndpoint != null

    /**
     * Runs [action] with the manager of the Local S3 started by this IDE, under the service lock so that it isn't
     * stopped meanwhile.
     *
     * @return the result of [action], or `null` if this IDE didn't start Local S3, e.g. it is stopped or shared with
     * another process, whose data can only be reached through the S3 API.
     */
    @Synchronized
    fun <T> withLocalManager(action: (LocalS3Manager) -> T): T? {
        val server = localS3?.takeIf { it.isRunning } ?: return null
        return action(server.s3Manager)
    }

    /**
     * Starts Local S3 when a project is opened, unless auto start is disabled or the user stopped it in this session.
     * A shared Local S3 is still taken over if it has stopped, since it was already in use.
     */
    fun autoStart(project: Project) {
        when {
            isShared() -> takeOverIfSharedServerGone(project)
            LocalS3Settings.getInstance().autoStart && !stoppedByUser -> start(project)
        }
    }

    /**
     * Starts Local S3, or reuses the one already listening on the configured port. Failures are reported with a
     * notification instead of being thrown, so they don't end up in the IDE error reporter.
     *
     * Calling it again is a no-op while Local S3 is available: a shared one is only probed again, and replaced by our
     * own if it has stopped, e.g. because the IDE that started it was closed.
     *
     * [LocalS3StateListener]s are notified each time Local S3 becomes available, never when it failed to start.
     *
     * @return `true` if Local S3 is available afterward, either started here or shared with another process.
     */
    @Synchronized
    fun start(project: Project? = null): Boolean {
        stoppedByUser = false
        if (isRunning()) {
            return true
        }
        val settings = LocalS3Settings.getInstance()
        sharedEndpoint?.let {
            if (isLocalS3Listening(settings)) {
                return true
            }
            thisLogger().info("Shared Local S3 at $it has stopped, starting our own")
            sharedEndpoint = null
        }
        if (reuseRunningLocalS3(settings, project)) {
            return true
        }
        try {
            localS3 = buildLocalS3(settings).also(LocalS3::start)
            publishStarted(settings, LocalS3Status.RUNNING)
            return true
        } catch (e: Exception) {
            closeQuietly()
            // Another IDE may have bound the port between the probe and our start.
            if (reuseRunningLocalS3(settings, project)) {
                return true
            }
            thisLogger().warn("Failed to start Local S3 at ${settings.endpoint}", e)
            updateStatus(LocalS3Status.FAILED)
            notifyStartFailure(settings, e, project)
            return false
        }
    }

    /**
     * Starts our own Local S3 if the shared one has stopped, so that the service doesn't silently disappear.
     */
    @Synchronized
    fun takeOverIfSharedServerGone(project: Project? = null) {
        if (isShared()) {
            start(project)
        }
    }

    /**
     * Takes over a shared Local S3 that has stopped, and detects our own server having stopped unexpectedly, so that
     * the status doesn't stay available while clients can't reach it.
     */
    private fun checkHealth() {
        when (status) {
            // Probe without the lock, so that actions don't wait for it; start() probes again before taking over.
            LocalS3Status.SHARED -> if (!isLocalS3Listening(LocalS3Settings.getInstance())) {
                takeOverIfSharedServerGone()
            }

            LocalS3Status.RUNNING -> detectUnexpectedStop()
            else -> Unit
        }
    }

    @Synchronized
    private fun detectUnexpectedStop() {
        val server = localS3 ?: return
        if (status != LocalS3Status.RUNNING || server.isRunning) {
            return
        }
        val settings = LocalS3Settings.getInstance()
        thisLogger().warn("Local S3 at ${settings.endpoint} has stopped unexpectedly")
        closeQuietly()
        updateStatus(LocalS3Status.FAILED)
        notify(
            null,
            NotificationType.ERROR,
            LocalS3Bundle["notification.unexpectedStop.title", settings.endpoint],
            LocalS3Bundle["notification.unexpectedStop.content"],
        ) {
            addAction(NotificationAction.createSimpleExpiring(LocalS3Bundle["notification.action.retry"]) {
                ApplicationManager.getApplication().executeOnPooledThread {
                    start()
                }
            })
        }
    }

    private fun scheduleHealthCheck(available: Boolean) {
        if (available && healthCheck == null) {
            healthCheck = AppExecutorUtil.getAppScheduledExecutorService().scheduleWithFixedDelay(
                { runCatching(::checkHealth).onFailure { thisLogger().warn("Local S3 health check failed", it) } },
                HEALTH_CHECK_INTERVAL_SECONDS,
                HEALTH_CHECK_INTERVAL_SECONDS,
                TimeUnit.SECONDS,
            )
        } else if (!available) {
            healthCheck?.cancel(false)
            healthCheck = null
        }
    }

    @Synchronized
    fun restart(project: Project? = null): Boolean {
        stop()
        return start(project)
    }

    /**
     * Restarts Local S3 off the EDT with a background progress, e.g. after the settings are applied.
     */
    fun restartInBackground(project: Project? = null) {
        object : Task.Backgroundable(project, LocalS3Bundle["progress.restart.title"], false) {
            override fun run(indicator: ProgressIndicator) {
                restart(project)
            }
        }.queue()
    }

    /**
     * @param byUser whether the user asked for it, which disables auto start for the rest of the IDE session.
     */
    @Synchronized
    fun stop(byUser: Boolean = false) {
        if (byUser) {
            stoppedByUser = true
        }
        shutdown()
        updateStatus(LocalS3Status.STOPPED)
    }

    /**
     * Unlike [stop], doesn't publish the status change: the application and its message bus are being disposed.
     */
    @Synchronized
    override fun dispose() {
        shutdown()
        scheduleHealthCheck(false)
        status = LocalS3Status.STOPPED
    }

    private fun shutdown() {
        sharedEndpoint = null
        localS3?.let {
            if (it.isRunning) {
                it.close()
            }
            localS3 = null
        }
    }

    private fun buildLocalS3(settings: LocalS3Settings): LocalS3 {
        val dataDirectory = settings.ensureDataDirectory()
        val builder = LocalS3.builder()
            .bindHost(settings.bindHost)
            .port(settings.port)
            .mode(LocalS3Mode.PERSISTENCE)
            .dataPath(dataDirectory.toString())
            .buckets(*settings.bucketNames.toTypedArray())
            .netty {
                it.daemonThreads(true).registerShutdownHook(false)
            }
        if (settings.accessKeyId.isNotEmpty() && settings.secretAccessKey.isNotEmpty()) {
            builder.credentials(settings.accessKeyId, settings.secretAccessKey)
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
        publishStarted(settings, LocalS3Status.SHARED)
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
            error.causes()
                .any { it is BindException || it.message.orEmpty().contains("Address already in use", true) } ->
                LocalS3Bundle["notification.startFailed.portInUse", settings.port]

            error.causes().any { it.message.orEmpty().contains("locked", true) } ->
                LocalS3Bundle["notification.startFailed.dataLocked", settings.dataPath]

            else -> LocalS3Bundle["notification.startFailed.other", error.causes().last().message
                ?: error.javaClass.simpleName]
        }
        notify(
            project,
            NotificationType.ERROR,
            LocalS3Bundle["notification.startFailed.title", settings.endpoint],
            content
        ) {
            addAction(NotificationAction.createSimpleExpiring(LocalS3Bundle["notification.action.openSettings"]) {
                ShowSettingsUtil.getInstance().showSettingsDialog(project, LocalS3Configurable::class.java)
            })
            addAction(NotificationAction.createSimpleExpiring(LocalS3Bundle["notification.action.retry"]) {
                ApplicationManager.getApplication().executeOnPooledThread {
                    start(project)
                }
            })
        }
    }

    private fun publishStarted(settings: LocalS3Settings, newStatus: LocalS3Status) {
        updateStatus(newStatus)
        publisher().started(settings.endpoint)
    }

    private fun updateStatus(newStatus: LocalS3Status) {
        scheduleHealthCheck(newStatus.isAvailable)
        if (status != newStatus) {
            status = newStatus
            publisher().statusChanged(newStatus)
        }
    }

    private fun publisher(): LocalS3StateListener =
        ApplicationManager.getApplication().messageBus.syncPublisher(LocalS3StateListener.TOPIC)

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

    private fun Throwable.causes(): List<Throwable> =
        generateSequence(this) { it.cause.takeIf { cause -> cause !== it } }.toList()

    companion object {
        const val NOTIFICATION_GROUP_ID = "Local S3 Notification"
        private const val HEALTH_CHECK_PATH = "/_health"
        private const val ADMIN_UI_PATH = "/_admin/ui"
        private const val CONNECT_TIMEOUT_MILLIS = 500
        private const val READ_TIMEOUT_MILLIS = 1000
        private const val HEALTH_CHECK_INTERVAL_SECONDS = 20L

        /**
         * Tells whether a Local S3 answers on the configured port, by its unauthenticated health check
         * `GET /_health`, which answers `{"status":"UP"}`.
         */
        internal fun isLocalS3Listening(settings: LocalS3Settings): Boolean = isLocalS3Listening(settings.endpoint)

        fun isLocalS3Listening(endpoint: String): Boolean {
            return try {
                val connection = URI("$endpoint$HEALTH_CHECK_PATH").toURL()
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

        fun adminUiUrl(endpoint: String): String = "$endpoint$ADMIN_UI_PATH"

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
