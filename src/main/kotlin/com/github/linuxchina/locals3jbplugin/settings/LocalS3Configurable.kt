package com.github.linuxchina.locals3jbplugin.settings

import com.github.linuxchina.locals3jbplugin.MyBundle
import com.github.linuxchina.locals3jbplugin.services.LocalS3Service
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.options.BoundConfigurable
import com.intellij.ui.dsl.builder.bindIntText
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.columns
import com.intellij.ui.dsl.builder.panel

class LocalS3Configurable : BoundConfigurable(MyBundle["settings.displayName"]) {
    private val settings = LocalS3Settings.getInstance()

    override fun createPanel() = panel {
        group(MyBundle["settings.server.group"]) {
            row(MyBundle["settings.host.label"]) {
                textField()
                    .bindText(settings::host)
                    .columns(24)
                    .validationOnApply {
                        if (it.text.isBlank()) error(MyBundle["settings.host.required"]) else null
                    }
            }
            row(MyBundle["settings.port.label"]) {
                intTextField(1..65535)
                    .bindIntText(settings::port)
                    .columns(8)
            }
            row {
                checkBox(MyBundle["settings.tls.label"])
                    .bindSelected(settings::tlsEnabled)
                    .comment(MyBundle["settings.tls.comment"])
            }
        }

        group(MyBundle["settings.storage.group"]) {
            row(MyBundle["settings.dataPath.label"]) {
                textField()
                    .bindText(settings::dataPath)
                    .columns(48)
                    .comment(MyBundle["settings.dataPath.comment"])
                    .validationOnApply {
                        if (it.text.isBlank()) error(MyBundle["settings.dataPath.required"]) else null
                    }
            }
        }

        group(MyBundle["settings.credentials.group"]) {
            row(MyBundle["settings.accessKeyId.label"]) {
                textField()
                    .bindText(settings::accessKeyId)
                    .columns(32)
                    .validationOnApply {
                        if (it.text.isBlank()) error(MyBundle["settings.accessKeyId.required"]) else null
                    }
            }
            row(MyBundle["settings.secretAccessKey.label"]) {
                passwordField()
                    .bindText(settings::secretAccessKey)
                    .columns(32)
                    .validationOnApply {
                        if (it.password.isEmpty()) error(MyBundle["settings.secretAccessKey.required"]) else null
                    }
            }
        }

        group(MyBundle["settings.buckets.group"]) {
            row(MyBundle["settings.buckets.label"]) {
                textField()
                    .bindText(settings::buckets)
                    .columns(48)
                    .comment(MyBundle["settings.buckets.comment"])
            }
        }
    }

    override fun apply() {
        val restartRequired = isModified
        super.apply()
        if (restartRequired) {
            service<LocalS3Service>().restart()
            ApplicationManager.getApplication().messageBus
                .syncPublisher(LocalS3SettingsListener.TOPIC)
                .settingsChanged()
        }
    }
}
