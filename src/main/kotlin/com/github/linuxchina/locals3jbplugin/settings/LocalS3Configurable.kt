package com.github.linuxchina.locals3jbplugin.settings

import com.github.linuxchina.locals3jbplugin.LocalS3Bundle
import com.github.linuxchina.locals3jbplugin.services.LocalS3Service
import com.github.linuxchina.locals3jbplugin.services.LocalS3StateListener
import com.github.linuxchina.locals3jbplugin.services.LocalS3Status
import com.google.common.net.InetAddresses
import com.intellij.icons.AllIcons
import com.intellij.ide.BrowserUtil
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.components.service
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.openapi.util.Disposer
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.Cell
import com.intellij.ui.dsl.builder.bindIntText
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.columns
import com.intellij.ui.dsl.builder.panel

class LocalS3Configurable : BoundConfigurable(LocalS3Bundle["settings.displayName"]) {
    private val settings = LocalS3Settings.getInstance()

    // Read-only connection info of the applied settings, refreshed on apply, reset and status changes.
    private val statusLabel = JBLabel()
    private val endpointLabel = JBLabel().apply { setCopyable(true) }
    private val accessKeyIdLabel = JBLabel().apply { setCopyable(true) }
    private val testResultLabel = JBLabel()

    private lateinit var hostField: Cell<JBTextField>
    private lateinit var portField: Cell<JBTextField>
    private lateinit var tlsCheckBox: Cell<JBCheckBox>

    /**
     * Disposed with the UI, so that the status subscription and a pending test don't outlive the settings dialog.
     */
    private var uiDisposable = Disposer.newDisposable()

    override fun createPanel() = panel {
        subscribeToStatus()
        group(LocalS3Bundle["settings.connection.group"]) {
            row(LocalS3Bundle["settings.connection.status.label"]) {
                cell(statusLabel)
            }
            row(LocalS3Bundle["settings.connection.endpoint.label"]) {
                cell(endpointLabel)
            }
            row(LocalS3Bundle["settings.connection.region.label"]) {
                label(LocalS3Settings.REGION)
            }
            row(LocalS3Bundle["settings.connection.accessKeyId.label"]) {
                cell(accessKeyIdLabel)
            }
            row {
                link(LocalS3Bundle["settings.connection.openAdminUi"]) {
                    BrowserUtil.browse(LocalS3Service.adminUiUrl(settings.endpoint))
                }
                link(LocalS3Bundle["settings.connection.test"]) {
                    testConnection()
                }
                cell(testResultLabel)
            }
        }

        group(LocalS3Bundle["settings.server.group"]) {
            row(LocalS3Bundle["settings.host.label"]) {
                hostField = textField()
                    .bindText(settings::host)
                    .columns(24)
                    .validationOnApply {
                        val host = it.text.trim()
                        when {
                            host.isEmpty() -> error(LocalS3Bundle["settings.host.required"])
                            !isIpAddress(host) -> error(LocalS3Bundle["settings.host.invalid"])
                            else -> null
                        }
                    }
            }
            row(LocalS3Bundle["settings.port.label"]) {
                portField = intTextField(1..65535)
                    .bindIntText(settings::port)
                    .columns(8)
            }
            row {
                tlsCheckBox = checkBox(LocalS3Bundle["settings.tls.label"])
                    .bindSelected(settings::tlsEnabled)
                    .comment(LocalS3Bundle["settings.tls.comment"])
            }
            row {
                checkBox(LocalS3Bundle["settings.autoStart.label"])
                    .bindSelected(settings::autoStart)
                    .comment(LocalS3Bundle["settings.autoStart.comment"])
            }
        }

        group(LocalS3Bundle["settings.storage.group"]) {
            row(LocalS3Bundle["settings.dataPath.label"]) {
                @Suppress("UnstableApiUsage")
                textFieldWithBrowseButton(FileChooserDescriptorFactory.createSingleFolderDescriptor())
                    .bindText(settings::dataPath)
                    .columns(48)
                    .comment(LocalS3Bundle["settings.dataPath.comment"])
                    .validationOnApply {
                        if (it.text.isBlank()) error(LocalS3Bundle["settings.dataPath.required"]) else null
                    }
            }
        }

        group(LocalS3Bundle["settings.credentials.group"]) {
            lateinit var accessKeyIdField: Cell<JBTextField>
            lateinit var secretAccessKeyField: Cell<JBPasswordField>
            row(LocalS3Bundle["settings.accessKeyId.label"]) {
                accessKeyIdField = textField()
                    .bindText(settings::accessKeyId)
                    .columns(32)
                    .validationOnApply {
                        if (it.text.isEmpty() && secretAccessKeyField.component.password.isNotEmpty()) {
                            error(LocalS3Bundle["settings.accessKeyId.required"])
                        } else null
                    }
            }
            row(LocalS3Bundle["settings.secretAccessKey.label"]) {
                secretAccessKeyField = passwordField()
                    .bindText(settings::secretAccessKey)
                    .columns(32)
                    .validationOnApply {
                        if (it.password.isEmpty() && accessKeyIdField.component.text.isNotEmpty()) {
                            error(LocalS3Bundle["settings.secretAccessKey.required"])
                        } else null
                    }
            }
            row {
                comment(LocalS3Bundle["settings.credentials.comment"])
            }
        }

        group(LocalS3Bundle["settings.buckets.group"]) {
            row(LocalS3Bundle["settings.buckets.label"]) {
                textField()
                    .bindText(settings::buckets)
                    .columns(48)
                    .comment(LocalS3Bundle["settings.buckets.comment"])
                    .validationOnApply { field ->
                        invalidBucketNames(field.text).takeIf { it.isNotEmpty() }?.let {
                            error(LocalS3Bundle["settings.buckets.invalid", it.joinToString(", ")])
                        }
                    }
            }
        }
    }

    private fun subscribeToStatus() {
        Disposer.dispose(uiDisposable)
        uiDisposable = Disposer.newDisposable()
        ApplicationManager.getApplication().messageBus.connect(uiDisposable)
            .subscribe(LocalS3StateListener.TOPIC, object : LocalS3StateListener {
                override fun started(endpoint: String) = Unit

                override fun statusChanged(status: LocalS3Status) {
                    ApplicationManager.getApplication().invokeLater(
                        ::updateConnectionInfo,
                        ModalityState.any(),
                        { Disposer.isDisposed(uiDisposable) },
                    )
                }
            })
    }

    override fun reset() {
        super.reset()
        updateConnectionInfo()
        testResultLabel.text = ""
        testResultLabel.icon = null
    }

    override fun apply() {
        val previousServerConfig = settings.serverConfig()
        super.apply()
        // A stopped server stays stopped, e.g. after the user stopped it or with auto start disabled: the new settings
        // are used on its next start. A failed one is retried with the new settings.
        val localS3Service = service<LocalS3Service>()
        if (settings.serverConfig() != previousServerConfig && localS3Service.status() != LocalS3Status.STOPPED) {
            localS3Service.restartInBackground()
        }
        updateConnectionInfo()
    }

    override fun disposeUIResources() {
        Disposer.dispose(uiDisposable)
        super.disposeUIResources()
    }

    private fun updateConnectionInfo() {
        statusLabel.text = service<LocalS3Service>().status().displayName
        endpointLabel.text = settings.endpoint
        accessKeyIdLabel.text = settings.accessKeyId.ifEmpty { LocalS3Bundle["settings.connection.anonymous"] }
    }

    /**
     * Probes the endpoint of the values in the form, which may not be applied yet.
     */
    private fun testConnection() {
        val port = portField.component.text.trim().toIntOrNull()
        if (port == null || port !in 1..65535) {
            showTestResult(false, LocalS3Bundle["settings.connection.test.invalidPort"])
            return
        }
        val endpoint = LocalS3Settings.endpointOf(hostField.component.text, port, tlsCheckBox.component.isSelected)
        testResultLabel.icon = AllIcons.Process.Step_1
        testResultLabel.text = LocalS3Bundle["settings.connection.test.running", endpoint]
        val disposable = uiDisposable
        ApplicationManager.getApplication().executeOnPooledThread {
            val listening = LocalS3Service.isLocalS3Listening(endpoint)
            ApplicationManager.getApplication().invokeLater({
                if (listening) {
                    showTestResult(true, LocalS3Bundle["settings.connection.test.success", endpoint])
                } else {
                    showTestResult(false, LocalS3Bundle["settings.connection.test.failure", endpoint])
                }
            }, ModalityState.any(), { Disposer.isDisposed(disposable) })
        }
    }

    private fun showTestResult(success: Boolean, message: String) {
        testResultLabel.icon = if (success) AllIcons.General.InspectionsOK else AllIcons.General.Error
        testResultLabel.text = message
    }

    companion object {
        private val BUCKET_NAME_REGEX = Regex("^[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]$")

        /**
         * Accepts IPv4 and IPv6 addresses, the latter optionally in brackets, e.g. `[::]`.
         */
        internal fun isIpAddress(host: String): Boolean =
            InetAddresses.isInetAddress(host.removeSurrounding("[", "]"))

        /**
         * Returns the names that break the S3 bucket naming rules, which would make Local S3 fail to start.
         */
        internal fun invalidBucketNames(buckets: String): List<String> = buckets.split(',')
            .map(String::trim)
            .filter(String::isNotEmpty)
            .filterNot { BUCKET_NAME_REGEX.matches(it) && ".." !in it && !InetAddresses.isInetAddress(it) }
            .distinct()
    }
}
