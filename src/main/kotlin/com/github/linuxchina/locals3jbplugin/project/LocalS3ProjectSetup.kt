package com.github.linuxchina.locals3jbplugin.project

import com.github.linuxchina.locals3jbplugin.LocalS3Bundle
import com.github.linuxchina.locals3jbplugin.services.LocalS3Service
import com.github.linuxchina.locals3jbplugin.services.LocalS3StateListener
import com.github.linuxchina.locals3jbplugin.services.LocalS3Status
import com.github.linuxchina.locals3jbplugin.settings.LocalS3Settings
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.text.HtmlChunk
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Applies the [LocalS3ProjectConfig] of the project, i.e. creates its buckets and imports its seed data, when the
 * project is opened and each time Local S3 starts afterward.
 *
 * Only a Local S3 started by this IDE is set up: a shared one belongs to another process, which is notified once.
 */
@Service(Service.Level.PROJECT)
class LocalS3ProjectSetup(private val project: Project) : Disposable {
    private val subscribed = AtomicBoolean()

    /**
     * Whether the user was told that the configuration isn't applied to the shared Local S3, see [notifySkippedIfShared].
     */
    private val sharedSkipNotified = AtomicBoolean()

    /**
     * Applies the configuration now, then each time Local S3 starts, e.g. after it was stopped or its settings changed.
     */
    fun applyAndSubscribe() {
        apply()
        if (subscribed.compareAndSet(false, true)) {
            ApplicationManager.getApplication().messageBus.connect(this)
                .subscribe(LocalS3StateListener.TOPIC, LocalS3StateListener {
                    // Notified under the lock of the service while it starts: apply once it has returned.
                    ApplicationManager.getApplication().executeOnPooledThread { apply() }
                })
        }
    }

    /**
     * Applies the configuration of the project, if it has one, to the Local S3 started by this IDE, if any, in a
     * background task.
     */
    fun apply() {
        if (project.isDisposed) {
            return
        }
        val projectDirectory = project.basePath?.let(Path::of) ?: return
        val config = loadOrNotify(projectDirectory) { LocalS3ProjectConfig.load(projectDirectory) } ?: return
        runInBackground(LocalS3Bundle["progress.applyProjectConfig.title"]) { indicator ->
            apply(config, projectDirectory, indicator)
        }
    }

    private fun apply(config: LocalS3ProjectConfig, projectDirectory: Path, indicator: ProgressIndicator) {
        val result = seed(config, projectDirectory, indicator)
        if (result == null) {
            thisLogger().info("Local S3 isn't started by this IDE, skipping ${LocalS3ProjectConfig.FILE_NAME} of ${project.name}")
            notifySkippedIfShared()
            return
        }
        // Applied to our own Local S3: tell again if it is shared with another process later on.
        sharedSkipNotified.set(false)
        report(result, byUser = false)
    }

    /**
     * Applies the configuration in [configText] on demand in a background task, resolving its local paths against
     * [baseDirectory], and always reports the outcome, even when there was nothing to change.
     */
    fun sync(configText: String, baseDirectory: Path) {
        runInBackground(LocalS3Bundle["progress.syncProjectConfig.title"]) { indicator ->
            if (!project.isDisposed) {
                sync(configText, baseDirectory, indicator)
            }
        }
    }

    private fun sync(configText: String, baseDirectory: Path, indicator: ProgressIndicator) {
        val config = loadOrNotify(baseDirectory) { LocalS3ProjectConfig.parse(configText) } ?: return
        val result = seed(config, baseDirectory, indicator)
        if (result == null) {
            notify(
                NotificationType.WARNING,
                LocalS3Bundle["notification.projectConfig.notRunning.title", LocalS3ProjectConfig.FILE_NAME],
                LocalS3Bundle["notification.projectConfig.notRunning.content"],
            )
            return
        }
        report(result, byUser = true)
    }

    private fun loadOrNotify(directory: Path, load: () -> LocalS3ProjectConfig?): LocalS3ProjectConfig? =
        try {
            load()
        } catch (e: Exception) {
            thisLogger().warn("Invalid ${LocalS3ProjectConfig.FILE_NAME} in $directory", e)
            notify(
                NotificationType.WARNING,
                LocalS3Bundle["notification.projectConfig.invalid.title", LocalS3ProjectConfig.FILE_NAME],
                HtmlChunk.text(e.message ?: e.javaClass.simpleName).toString(),
            )
            null
        }

    /**
     * Seeds without holding the lock of the service: stopping Local S3 cancels [indicator] instead of waiting for the
     * whole import.
     *
     * @return the result, or `null` if Local S3 isn't started by this IDE.
     */
    private fun seed(
        config: LocalS3ProjectConfig,
        baseDirectory: Path,
        indicator: ProgressIndicator,
    ): LocalS3ProjectSeeder.Result? =
        service<LocalS3Service>().withLocalManager(indicator) { manager ->
            LocalS3ProjectSeeder(manager, baseDirectory).apply(config, indicator)
        }

    /**
     * Runs [block] in a cancelable background task, which shows the progress of the import.
     */
    private fun runInBackground(title: String, block: (ProgressIndicator) -> Unit) {
        object : Task.Backgroundable(project, title, true) {
            override fun run(indicator: ProgressIndicator) {
                block(indicator)
            }
        }.queue()
    }

    /**
     * Tells once, until the configuration is applied again, that it isn't applied to a shared Local S3, so that missing
     * buckets don't come as a surprise. A stopped or failed Local S3 already has its own notifications.
     */
    private fun notifySkippedIfShared() {
        if (service<LocalS3Service>().status() != LocalS3Status.SHARED || !sharedSkipNotified.compareAndSet(false, true)) {
            return
        }
        notify(
            NotificationType.WARNING,
            LocalS3Bundle["notification.projectConfig.shared.title", LocalS3ProjectConfig.FILE_NAME],
            LocalS3Bundle[
                "notification.projectConfig.shared.content",
                LocalS3Settings.getInstance().endpoint,
                LocalS3ProjectConfig.FILE_NAME,
            ],
        )
    }

    private fun report(result: LocalS3ProjectSeeder.Result, byUser: Boolean) {
        if (result.hasChanges) {
            notify(
                NotificationType.INFORMATION,
                LocalS3Bundle["notification.projectConfig.applied.title"],
                LocalS3Bundle[
                    "notification.projectConfig.applied.content",
                    result.createdBuckets.size,
                    result.importedObjects,
                    LocalS3ProjectConfig.FILE_NAME,
                ],
            )
        }
        val problems = result.truncatedDirectories.map {
            LocalS3Bundle["notification.projectConfig.truncated", it, LocalS3ProjectSeeder.MAX_FILES_PER_DIRECTORY]
        } + result.errors
        if (problems.isNotEmpty()) {
            problems.forEach { thisLogger().warn(it) }
            val shown = problems.take(MAX_PROBLEMS_SHOWN).map { HtmlChunk.text(it) } +
                    listOfNotNull(
                        (problems.size - MAX_PROBLEMS_SHOWN).takeIf { it > 0 }?.let {
                            HtmlChunk.text(LocalS3Bundle["notification.projectConfig.moreProblems", it])
                        }
                    )
            notify(
                NotificationType.WARNING,
                LocalS3Bundle["notification.projectConfig.problems.title", LocalS3ProjectConfig.FILE_NAME],
                HtmlChunk.fragment(*shown.flatMapIndexed { i, chunk ->
                    if (i == 0) listOf(chunk) else listOf(HtmlChunk.br(), chunk)
                }.toTypedArray()).toString(),
            )
        } else if (byUser && !result.hasChanges) {
            notify(
                NotificationType.INFORMATION,
                LocalS3Bundle["notification.projectConfig.upToDate.title"],
                LocalS3Bundle["notification.projectConfig.upToDate.content", LocalS3ProjectConfig.FILE_NAME],
            )
        }
    }

    private fun notify(type: NotificationType, title: String, content: String) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup(LocalS3Service.NOTIFICATION_GROUP_ID)
            .createNotification(title, content, type)
            .notify(project)
    }

    override fun dispose() = Unit

    private companion object {
        const val MAX_PROBLEMS_SHOWN = 5
    }
}
