package com.github.linuxchina.locals3jbplugin.project

import com.github.linuxchina.locals3jbplugin.LocalS3Bundle
import com.github.linuxchina.locals3jbplugin.services.LocalS3Service
import com.github.linuxchina.locals3jbplugin.services.LocalS3StateListener
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.text.HtmlChunk
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Applies the [LocalS3ProjectConfig] of the project, i.e. creates its buckets and imports its seed data, when the
 * project is opened and each time Local S3 starts afterward.
 *
 * Only a Local S3 started by this IDE is set up: a shared one belongs to another process.
 */
@Service(Service.Level.PROJECT)
class LocalS3ProjectSetup(private val project: Project) : Disposable {
    private val subscribed = AtomicBoolean()

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
     * Applies the configuration of the project, if it has one, to the Local S3 started by this IDE, if any.
     */
    fun apply() {
        if (project.isDisposed) {
            return
        }
        val projectDirectory = project.basePath?.let(Path::of) ?: return
        val config = loadOrNotify(projectDirectory) { LocalS3ProjectConfig.load(projectDirectory) } ?: return
        val result = seed(config, projectDirectory)
        if (result == null) {
            thisLogger().info("Local S3 isn't started by this IDE, skipping ${LocalS3ProjectConfig.FILE_NAME} of ${project.name}")
            return
        }
        report(result, byUser = false)
    }

    /**
     * Applies the configuration in [configText] on demand, resolving its local paths against [baseDirectory], and
     * always reports the outcome, even when there was nothing to change.
     */
    fun sync(configText: String, baseDirectory: Path) {
        if (project.isDisposed) {
            return
        }
        val config = loadOrNotify(baseDirectory) { LocalS3ProjectConfig.parse(configText) } ?: return
        val result = seed(config, baseDirectory)
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
     * @return the result, or `null` if Local S3 isn't started by this IDE.
     */
    private fun seed(config: LocalS3ProjectConfig, baseDirectory: Path): LocalS3ProjectSeeder.Result? =
        service<LocalS3Service>().withLocalManager { manager ->
            LocalS3ProjectSeeder(manager, baseDirectory).apply(config)
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
