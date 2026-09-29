package com.github.linuxchina.locals3jbplugin.settings

import com.github.linuxchina.locals3jbplugin.LocalS3Bundle
import com.github.linuxchina.locals3jbplugin.services.LocalS3Service
import com.google.common.net.InetAddresses
import com.intellij.openapi.components.service
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.ui.ValidationInfo
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

    override fun createPanel() = panel {
        group(LocalS3Bundle["settings.server.group"]) {
            row(LocalS3Bundle["settings.host.label"]) {
                textField()
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
                intTextField(1..65535)
                    .bindIntText(settings::port)
                    .columns(8)
            }
            row {
                checkBox(LocalS3Bundle["settings.tls.label"])
                    .bindSelected(settings::tlsEnabled)
                    .comment(LocalS3Bundle["settings.tls.comment"])
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

    override fun apply() {
        val previousServerConfig = settings.serverConfig()
        super.apply()
        if (settings.serverConfig() != previousServerConfig) {
            service<LocalS3Service>().restartInBackground()
        }
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
