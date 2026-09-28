package com.github.linuxchina.locals3jbplugin.startup

import com.github.linuxchina.locals3jbplugin.settings.LocalS3Settings
import com.intellij.bigdatatools.awsBase.connection.auth.AuthenticationType
import com.intellij.bigdatatools.awsBase.settings.AwsCompatibleConnectionDataBase
import com.intellij.credentialStore.Credentials
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.jetbrains.bigdatatools.common.filestorages.BucketFilterType
import com.jetbrains.bigdatatools.common.settings.GlobalConnectionSettings
import com.jetbrains.bigdatatools.common.settings.manager.RfsConnectionDataManager
import kotlinx.coroutines.delay
import org.com.jetbrains.bigdatatools.S3ConnectionGroup
import org.com.jetbrains.bigdatatools.aws.s3.S3ConnectionData
import kotlin.time.Duration.Companion.seconds

class CheckLocalS3Activity : ProjectActivity {
    override suspend fun execute(project: Project) {
        delay(12.seconds)
        checkLocalS3Connect(project)
    }

    fun checkLocalS3Connect(project: Project) {
        val connectionDataManager = RfsConnectionDataManager.instance ?: return
        val existingConnection = connectionDataManager.getConnections(project).firstOrNull {
            it.innerId == LOCAL_S3_CONNECTION_ID ||
                    it is S3ConnectionData && (it.name == LOCAL_S3_CONNECTION_NAME || it.name == LOCAL_S3_CONNECTION_NAME2)
        }

        if (existingConnection?.innerId == LOCAL_S3_CONNECTION_ID &&
            existingConnection is S3ConnectionData
        ) {
            return
        }

        val settings = LocalS3Settings.getInstance()
        val connectionData = S3ConnectionGroup().createBlankData(
            LOCAL_S3_CONNECTION_ID,
            false
        ).apply {
            name = LOCAL_S3_CONNECTION_NAME
            useCustomEndpoint = true
            customEndpoint = settings.endpoint
            customRegion = LOCAL_S3_DEFAULT_REGION
            region = LOCAL_S3_DEFAULT_REGION
            activeAuthenticationType = AuthenticationType.KEY_PAIR.id
            setCredentials(
                Credentials(settings.accessKeyId, settings.secretAccessKey),
                AwsCompatibleConnectionDataBase.SECRET_KEY_ID
            )
            profileName = "default"
            isEnabled = true
            isMultiBucketSupport = true
            isBucketSourceCustom = false
            bucketFilterType = BucketFilterType.CONTAINS
            bucketFilter = ""
            bucket = ""
            trustAllSsl = settings.tlsEnabled
        }
        GlobalConnectionSettings.getInstance().addConnection(connectionData)
    }

    private companion object {
        const val LOCAL_S3_CONNECTION_ID = "com.github.linuxchina.locals3.connection"
        const val LOCAL_S3_CONNECTION_NAME = "Local S3"
        const val LOCAL_S3_CONNECTION_NAME2 = "LocalS3"
        const val LOCAL_S3_DEFAULT_REGION = "us-east-1"
    }
}
