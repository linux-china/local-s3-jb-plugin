package com.github.linuxchina.locals3jbplugin.actions

import com.github.linuxchina.locals3jbplugin.LocalS3Bundle
import com.github.linuxchina.locals3jbplugin.services.LocalS3Service
import com.github.linuxchina.locals3jbplugin.services.LocalS3Status
import com.github.linuxchina.locals3jbplugin.settings.LocalS3Configurable
import com.github.linuxchina.locals3jbplugin.settings.LocalS3Settings
import com.intellij.ide.BrowserUtil
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.components.service
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import java.awt.datatransfer.StringSelection

abstract class LocalS3Action : DumbAwareAction() {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = isEnabled(service<LocalS3Service>().status())
    }

    protected open fun isEnabled(status: LocalS3Status): Boolean = true

    /**
     * Starting and stopping may take a while (port probe, Netty shutdown), so keep them off the EDT.
     */
    protected fun runInBackground(project: Project?, titleKey: String, block: (LocalS3Service) -> Unit) {
        object : Task.Backgroundable(project, LocalS3Bundle.message(titleKey), false) {
            override fun run(indicator: ProgressIndicator) {
                block(service<LocalS3Service>())
            }
        }.queue()
    }
}

class StartLocalS3Action : LocalS3Action() {
    override fun isEnabled(status: LocalS3Status) = !status.isAvailable

    override fun actionPerformed(e: AnActionEvent) {
        runInBackground(e.project, "progress.start.title") { it.start(e.project) }
    }
}

/**
 * Only stops the server started by this IDE: a shared one belongs to another process.
 */
class StopLocalS3Action : LocalS3Action() {
    override fun isEnabled(status: LocalS3Status) = status == LocalS3Status.RUNNING

    override fun actionPerformed(e: AnActionEvent) {
        runInBackground(e.project, "progress.stop.title") { it.stop() }
    }
}

class RestartLocalS3Action : LocalS3Action() {
    override fun actionPerformed(e: AnActionEvent) {
        runInBackground(e.project, "progress.restart.title") { it.restart(e.project) }
    }
}

class OpenLocalS3AdminUiAction : LocalS3Action() {
    override fun isEnabled(status: LocalS3Status) = status.isAvailable

    override fun actionPerformed(e: AnActionEvent) {
        BrowserUtil.browse(LocalS3Service.adminUiUrl(LocalS3Settings.getInstance().endpoint))
    }
}

class CopyLocalS3EndpointAction : LocalS3Action() {
    override fun actionPerformed(e: AnActionEvent) {
        CopyPasteManager.getInstance().setContents(StringSelection(LocalS3Settings.getInstance().endpoint))
    }
}

class OpenLocalS3SettingsAction : LocalS3Action() {
    override fun actionPerformed(e: AnActionEvent) {
        ShowSettingsUtil.getInstance().showSettingsDialog(e.project, LocalS3Configurable::class.java)
    }
}
