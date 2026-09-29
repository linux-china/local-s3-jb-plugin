package com.github.linuxchina.locals3jbplugin.statusbar

import com.github.linuxchina.locals3jbplugin.LocalS3Bundle
import com.github.linuxchina.locals3jbplugin.LocalS3Icons
import com.github.linuxchina.locals3jbplugin.services.LocalS3Service
import com.github.linuxchina.locals3jbplugin.services.LocalS3StateListener
import com.github.linuxchina.locals3jbplugin.services.LocalS3Status
import com.github.linuxchina.locals3jbplugin.settings.LocalS3Settings
import com.intellij.ide.DataManager
import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.ui.popup.PopupShowOptions
import com.intellij.openapi.wm.StatusBar
import com.intellij.openapi.wm.StatusBarWidget
import com.intellij.openapi.wm.StatusBarWidgetFactory
import com.intellij.util.Consumer
import java.awt.event.MouseEvent
import javax.swing.Icon

class LocalS3StatusBarWidgetFactory : StatusBarWidgetFactory {
    override fun getId() = LocalS3StatusBarWidget.ID

    override fun getDisplayName() = LocalS3Bundle.message("statusBar.displayName")

    override fun createWidget(project: Project): StatusBarWidget = LocalS3StatusBarWidget()
}

/**
 * Shows the Local S3 icon, reflecting the status, with details in its tooltip, and the Local S3 actions on click.
 */
class LocalS3StatusBarWidget : StatusBarWidget, StatusBarWidget.IconPresentation {
    private var statusBar: StatusBar? = null

    override fun ID() = ID

    override fun install(statusBar: StatusBar) {
        this.statusBar = statusBar
        ApplicationManager.getApplication().messageBus.connect(this)
            .subscribe(LocalS3StateListener.TOPIC, object : LocalS3StateListener {
                override fun started(endpoint: String) = Unit

                override fun statusChanged(status: LocalS3Status) {
                    ApplicationManager.getApplication()
                        .invokeLater { this@LocalS3StatusBarWidget.statusBar?.updateWidget(ID) }
                }
            })
    }

    override fun dispose() {
        statusBar = null
    }

    override fun getPresentation() = this

    override fun getIcon(): Icon = when (status()) {
        LocalS3Status.RUNNING -> LocalS3Icons.Bucket
        LocalS3Status.SHARED -> LocalS3Icons.BucketShared
        LocalS3Status.STOPPED -> LocalS3Icons.BucketStopped
        LocalS3Status.FAILED -> LocalS3Icons.BucketFailed
    }

    override fun getTooltipText(): String {
        val status = status()
        return if (status.isAvailable) {
            LocalS3Bundle.message(
                "statusBar.tooltip.available",
                status.displayName,
                LocalS3Settings.getInstance().endpoint
            )
        } else {
            LocalS3Bundle.message("statusBar.tooltip", status.displayName)
        }
    }

    override fun getClickConsumer() = Consumer<MouseEvent> { event ->
        val component = event.component ?: return@Consumer
        val group = ActionManager.getInstance().getAction(ACTION_GROUP_ID) as? ActionGroup ?: return@Consumer
        val popup = JBPopupFactory.getInstance().createActionGroupPopup(
            LocalS3Bundle.message("statusBar.popup.title"),
            group,
            DataManager.getInstance().getDataContext(component),
            JBPopupFactory.ActionSelectionAid.SPEEDSEARCH,
            false,
        )
        popup.show(PopupShowOptions.aboveComponent(component))
    }

    private fun status() = service<LocalS3Service>().status()

    companion object {
        const val ID = "LocalS3"
        private const val ACTION_GROUP_ID = "LocalS3.Actions"
    }
}
