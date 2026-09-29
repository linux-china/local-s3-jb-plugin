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
        runInBackground(e.project, "progress.stop.title") { it.stop(byUser = true) }
    }
}

/**
 * Disabled for a shared server: it belongs to another process, so there is nothing to restart here.
 */
class RestartLocalS3Action : LocalS3Action() {
    override fun isEnabled(status: LocalS3Status) = status != LocalS3Status.SHARED

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

/**
 * Copies the settings to connect to Local S3 in a client specific format, from the "Copy Connection As" submenu.
 */
abstract class CopyLocalS3ConnectionAction : LocalS3Action() {
    override fun actionPerformed(e: AnActionEvent) {
        CopyPasteManager.getInstance().setContents(StringSelection(connection(LocalS3Settings.getInstance())))
    }

    protected abstract fun connection(settings: LocalS3Settings): String

    companion object {
        /**
         * Without credentials, Local S3 accepts anonymous requests.
         */
        fun hasCredentials(settings: LocalS3Settings): Boolean =
            settings.accessKeyId.isNotEmpty() && settings.secretAccessKey.isNotEmpty()
    }
}

/**
 * Copies the environment variables that AWS SDKs and CLI read to reach Local S3, in `.env` format.
 */
class CopyLocalS3DotEnvAction : CopyLocalS3ConnectionAction() {
    override fun connection(settings: LocalS3Settings) = dotEnv(settings)

    companion object {
        fun dotEnv(settings: LocalS3Settings): String = buildString {
            appendLine("AWS_ENDPOINT_URL=${settings.endpoint}")
            appendLine("AWS_ENDPOINT_URL_S3=${settings.endpoint}")
            appendLine("AWS_REGION=${LocalS3Settings.REGION}")
            appendLine("AWS_DEFAULT_REGION=${LocalS3Settings.REGION}")
            if (hasCredentials(settings)) {
                appendLine("AWS_ACCESS_KEY_ID=${settings.accessKeyId}")
                appendLine("AWS_SECRET_ACCESS_KEY=${settings.secretAccessKey}")
            }
            settings.bucketNames.firstOrNull()?.let { appendLine("AWS_S3_BUCKET=$it") }
        }
    }
}

/**
 * Copies a DuckDB `httpfs` S3 secret. DuckDB always connects without TLS, as it doesn't trust the self-signed certificate.
 */
class CopyLocalS3DuckDbSecretAction : CopyLocalS3ConnectionAction() {
    override fun connection(settings: LocalS3Settings) = duckDbSecret(settings)

    companion object {
        fun duckDbSecret(settings: LocalS3Settings): String = buildString {
            appendLine("CREATE OR REPLACE SECRET locals3 (")
            appendLine("    TYPE s3,")
            if (hasCredentials(settings)) {
                appendLine("    KEY_ID ${sqlString(settings.accessKeyId)},")
                appendLine("    SECRET ${sqlString(settings.secretAccessKey)},")
            }
            appendLine("    REGION '${LocalS3Settings.REGION}',")
            appendLine("    ENDPOINT '${settings.clientHost}:${settings.port}',")
            appendLine("    URL_STYLE 'path',")
            appendLine("    USE_SSL false")
            appendLine(");")
        }

        private fun sqlString(value: String) = "'${value.replace("'", "''")}'"
    }
}

/**
 * Copies Spring Cloud AWS S3 settings in Spring Boot `application.properties` format.
 */
class CopyLocalS3SpringBootPropertiesAction : CopyLocalS3ConnectionAction() {
    override fun connection(settings: LocalS3Settings) = springBootProperties(settings)

    companion object {
        fun springBootProperties(settings: LocalS3Settings): String = buildString {
            appendLine("spring.cloud.aws.s3.endpoint=${settings.endpoint}")
            appendLine("spring.cloud.aws.s3.path-style-access-enabled=true")
            appendLine("spring.cloud.aws.region.static=${LocalS3Settings.REGION}")
            if (hasCredentials(settings)) {
                appendLine("spring.cloud.aws.credentials.access-key=${settings.accessKeyId}")
                appendLine("spring.cloud.aws.credentials.secret-key=${settings.secretAccessKey}")
            }
        }
    }
}

/**
 * Copies an AWS CLI profile for `~/.aws/config`, used with `aws --profile locals3`.
 */
class CopyLocalS3AwsCliProfileAction : CopyLocalS3ConnectionAction() {
    override fun connection(settings: LocalS3Settings) = awsCliProfile(settings)

    companion object {
        const val PROFILE_NAME = "locals3"

        fun awsCliProfile(settings: LocalS3Settings): String = buildString {
            appendLine("[profile $PROFILE_NAME]")
            appendLine("region = ${LocalS3Settings.REGION}")
            appendLine("endpoint_url = ${settings.endpoint}")
            if (hasCredentials(settings)) {
                appendLine("aws_access_key_id = ${settings.accessKeyId}")
                appendLine("aws_secret_access_key = ${settings.secretAccessKey}")
            }
            appendLine("s3 =")
            appendLine("    addressing_style = path")
        }
    }
}

class OpenLocalS3SettingsAction : LocalS3Action() {
    override fun actionPerformed(e: AnActionEvent) {
        ShowSettingsUtil.getInstance().showSettingsDialog(e.project, LocalS3Configurable::class.java)
    }
}
