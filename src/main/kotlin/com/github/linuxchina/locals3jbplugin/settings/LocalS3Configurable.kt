package com.github.linuxchina.locals3jbplugin.settings

import com.github.linuxchina.locals3jbplugin.LocalS3Bundle
import com.github.linuxchina.locals3jbplugin.services.LocalS3Service
import com.intellij.openapi.components.service
import com.intellij.openapi.options.BoundConfigurable
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
                        if (it.text.isBlank()) error(LocalS3Bundle["settings.host.required"]) else null
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
                textFieldWithBrowseButton()
                    .bindText(settings::dataPath)
                    .columns(48)
                    .comment(LocalS3Bundle["settings.dataPath.comment"])
                    .validationOnApply {
                        if (it.text.isBlank()) error(LocalS3Bundle["settings.dataPath.required"]) else null
                    }
            }
        }

        group(LocalS3Bundle["settings.credentials.group"]) {
            row(LocalS3Bundle["settings.accessKeyId.label"]) {
                textField()
                    .bindText(settings::accessKeyId)
                    .columns(32)
                    .validationOnApply {
                        if (it.text.isBlank()) error(LocalS3Bundle["settings.accessKeyId.required"]) else null
                    }
            }
            row(LocalS3Bundle["settings.secretAccessKey.label"]) {
                passwordField()
                    .bindText(settings::secretAccessKey)
                    .columns(32)
                    .validationOnApply {
                        if (it.password.isEmpty()) error(LocalS3Bundle["settings.secretAccessKey.required"]) else null
                    }
            }
        }

        group(LocalS3Bundle["settings.buckets.group"]) {
            row(LocalS3Bundle["settings.buckets.label"]) {
                textField()
                    .bindText(settings::buckets)
                    .columns(48)
                    .comment(LocalS3Bundle["settings.buckets.comment"])
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
}
