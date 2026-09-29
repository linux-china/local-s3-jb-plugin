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
import com.robothy.s3.core.exception.DataPathLockedException
import com.robothy.s3.core.service.manager.LocalS3Manager
import com.robothy.s3.rest.LocalS3
import com.robothy.s3.rest.bootstrap.LocalS3Mode
import java.net.BindException
import java.net.HttpURLConnection
import java.net.Proxy
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.write

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
     * Whether a [checkHealth] is running, so that a slow one isn't overlapped by the next.
     */
    private val healthChecking = AtomicBoolean()

    /**
     * Held for reading while [withLocalManager] runs, and for writing while our Local S3 is closed, so that it isn't
     * closed under an action without holding the service lock for the whole action, e.g. a long seed import.
     */
    private val managerLock = ReentrantReadWriteLock()

    /**
     * The indicators of the running [withLocalManager] actions, canceled when our Local S3 is closed so that closing
     * doesn't wait for them to complete.
     */
    private val managerUsers: MutableSet<ProgressIndicator> = ConcurrentHashMap.newKeySet()

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
     * Runs [action] with the manager of the Local S3 started by this IDE, which isn't closed meanwhile. It doesn't hold
     * the service lock, so that starting, stopping and the health check don't wait for [action].
     *
     * @param indicator canceled when Local S3 is closed, which waits for [action] to return: a long action should check
     * it regularly.
     * @return the result of [action], or `null` if this IDE didn't start Local S3, e.g. it is stopped or shared with
     * another process, whose data can only be reached through the S3 API.
     */
    fun <T> withLocalManager(indicator: ProgressIndicator? = null, action: (LocalS3Manager) -> T): T? {
        val manager = synchronized(this) {
            val server = localS3?.takeIf { it.isRunning } ?: return null
            // Registered under the service lock, so that closing can't miss it.
            managerLock.readLock().lock()
            indicator?.let(managerUsers::add)
            server.s3Manager
        }
        try {
            return action(manager)
        } finally {
            indicator?.let(managerUsers::remove)
            managerLock.readLock().unlock()
        }
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
    fun start(project: Project? = null): Boolean {
        val settings = LocalS3Settings.getInstance()
        val race = synchronized(this) {
            when (val result = tryStart(settings, project)) {
                StartResult.Available -> return true
                StartResult.Failed -> return false
                is StartResult.Race -> result
            }
        }
        // Another IDE may have bound the port or locked the data directory between the probe and our start. It locks
        // the data directory before binding the port, so give it some time to start listening, without the service
        // lock so that other callers, e.g. a stop, don't wait meanwhile.
        val listening = awaitListening(settings)
        synchronized(this) {
            when {
                isRunning() || isShared() -> return true
                // Stopped by the user meanwhile: don't reuse the other Local S3 behind their back.
                stoppedByUser -> return false
                listening && reuseRunningLocalS3(settings, project) -> return true
            }
            reportStartFailure(settings, race.error, project)
            return false
        }
    }

    private sealed interface StartResult {
        data object Available : StartResult
        data object Failed : StartResult

        /**
         * Failed to start because another process bound the port or locked the data directory meanwhile.
         */
        class Race(val error: Exception) : StartResult
    }

    /**
     * Starts Local S3 or reuses the one already listening, under the service lock.
     */
    private fun tryStart(settings: LocalS3Settings, project: Project?): StartResult {
        stoppedByUser = false
        if (isRunning()) {
            return StartResult.Available
        }
        val takingOver = sharedEndpoint?.let {
            if (isLocalS3Listening(settings)) {
                return StartResult.Available
            }
            thisLogger().info("Shared Local S3 at $it has stopped, starting our own")
            sharedEndpoint = null
            true
        } ?: false
        if (reuseRunningLocalS3(settings, project)) {
            return StartResult.Available
        }
        try {
            val server = buildLocalS3(settings).also(LocalS3::start)
            localS3 = server
            publishStarted(settings, LocalS3Status.RUNNING)
            if (takingOver) {
                notifyTakeOver(settings, server, project)
            }
            return StartResult.Available
        } catch (e: Exception) {
            closeQuietly()
            if (e.isRaceWithOtherProcess()) {
                return StartResult.Race(e)
            }
            if (reuseRunningLocalS3(settings, project)) {
                return StartResult.Available
            }
            reportStartFailure(settings, e, project)
            return StartResult.Failed
        }
    }

    private fun reportStartFailure(settings: LocalS3Settings, error: Exception, project: Project?) {
        thisLogger().warn("Failed to start Local S3 at ${settings.endpoint}", error)
        updateStatus(LocalS3Status.FAILED)
        notifyStartFailure(settings, error, project)
    }

    /**
     * Starts our own Local S3 if the shared one has stopped, so that the service doesn't silently disappear. [start]
     * checks again under the service lock whether it is still shared.
     */
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

    /**
     * The shared scheduler only triggers [checkHealth], which probes the port and may start Netty, on a pooled thread.
     */
    private fun scheduleHealthCheck(available: Boolean) {
        if (available && healthCheck == null) {
            healthCheck = AppExecutorUtil.getAppScheduledExecutorService().scheduleWithFixedDelay(
                ::triggerHealthCheck,
                HEALTH_CHECK_INTERVAL_SECONDS,
                HEALTH_CHECK_INTERVAL_SECONDS,
                TimeUnit.SECONDS,
            )
        } else if (!available) {
            healthCheck?.cancel(false)
            healthCheck = null
        }
    }

    private fun triggerHealthCheck() {
        if (!healthChecking.compareAndSet(false, true)) {
            return
        }
        AppExecutorUtil.getAppExecutorService().execute {
            try {
                runCatching(::checkHealth).onFailure { thisLogger().warn("Local S3 health check failed", it) }
            } finally {
                healthChecking.set(false)
            }
        }
    }

    /**
     * Not under the service lock as a whole, so that [start] can wait for a concurrent start by another process
     * without it; a concurrent start is fine either way, since starting is a no-op while Local S3 is available.
     */
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
     * Creates the missing [buckets] off the EDT, e.g. after only the default buckets are changed in the settings. The
     * Local S3 started by this IDE creates them in place, without a restart; otherwise, e.g. a failed one, it is
     * restarted so that they are created on start.
     */
    fun createBucketsInBackground(buckets: List<String>, project: Project? = null) {
        object : Task.Backgroundable(project, LocalS3Bundle["progress.createBuckets.title"], false) {
            override fun run(indicator: ProgressIndicator) {
                withLocalManager { createMissingBuckets(it, buckets) } ?: restart(project)
            }
        }.queue()
    }

    private fun createMissingBuckets(manager: LocalS3Manager, buckets: List<String>) {
        for (bucket in buckets) {
            runCatching { manager.createBucketIfAbsent(bucket) }
                .onFailure { thisLogger().warn("Failed to create bucket $bucket", it) }
        }
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
                closeServer(it)
            }
            localS3 = null
        }
    }

    /**
     * Closes [server] once the [withLocalManager] actions using it have returned, canceling them first so that it
     * doesn't wait for a whole seed import. Called under the service lock, so that no new action starts meanwhile.
     */
    private fun closeServer(server: LocalS3) {
        managerUsers.forEach(ProgressIndicator::cancel)
        managerLock.write {
            server.close()
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
                it.certificate(LocalS3TlsCertificates.loadOrCreate()).required(false)
            }
        }
        if (settings.icebergEnabled) {
            // Its warehouse bucket is created on start, and its tables are stored in the data path like any object.
            builder.icebergCatalog {
                it.warehouse(settings.icebergWarehouse)
            }
        }
        return builder.build()
    }

    private fun closeQuietly() {
        try {
            localS3?.let(::closeServer)
        } catch (e: Exception) {
            thisLogger().debug("Failed to close Local S3 after a failed start", e)
        }
        localS3 = null
    }

    /**
     * Waits for another process that is starting Local S3 concurrently to listen. Only probes, without changing the
     * state, so that it can run without the service lock.
     */
    private fun awaitListening(settings: LocalS3Settings): Boolean {
        repeat(RACE_PROBE_ATTEMPTS) { attempt ->
            if (attempt > 0) {
                Thread.sleep(RACE_PROBE_DELAY_MILLIS)
            }
            if (isLocalS3Listening(settings)) {
                return true
            }
        }
        return false
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

    /**
     * Tells that the shared Local S3 was replaced by our own, whose data path differs, so that buckets and objects
     * that seem to have vanished don't come as a surprise.
     */
    private fun notifyTakeOver(settings: LocalS3Settings, server: LocalS3, project: Project?) {
        notify(
            project,
            NotificationType.WARNING,
            LocalS3Bundle["notification.takeOver.title", settings.endpoint],
            LocalS3Bundle["notification.takeOver.content", server.dataPath],
        )
    }

    private fun notifyStartFailure(settings: LocalS3Settings, error: Exception, project: Project?) {
        val content = when {
            error.isPortInUse() -> LocalS3Bundle["notification.startFailed.portInUse", settings.port]
            error.isDataLocked() -> LocalS3Bundle["notification.startFailed.dataLocked", settings.dataPath]

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

    private fun Throwable.isPortInUse(): Boolean =
        causes().any { it is BindException || it.message.orEmpty().contains("Address already in use", true) }

    private fun Throwable.isDataLocked(): Boolean = causes().any { it is DataPathLockedException }

    private fun Throwable.isRaceWithOtherProcess(): Boolean = isPortInUse() || isDataLocked()

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
         * How long to wait for another process that locked the data directory or bound the port to start listening.
         */
        private const val RACE_PROBE_ATTEMPTS = 10
        private const val RACE_PROBE_DELAY_MILLIS = 300L

        /**
         * Tells whether a Local S3 answers on the configured port, by its unauthenticated health check
         * `GET /_health`, which answers `{"status":"UP"}`.
         */
        internal fun isLocalS3Listening(settings: LocalS3Settings): Boolean = isLocalS3Listening(settings.httpEndpoint)

        fun isLocalS3Listening(httpEndpoint: String): Boolean {
            return try {
                val connection = URI("$httpEndpoint$HEALTH_CHECK_PATH").toURL()
                    .openConnection(Proxy.NO_PROXY) as HttpURLConnection
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
    }
}
