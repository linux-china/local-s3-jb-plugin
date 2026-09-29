package com.github.linuxchina.locals3jbplugin.startup

import com.github.linuxchina.locals3jbplugin.services.LocalS3StateListener
import com.github.linuxchina.locals3jbplugin.settings.LocalS3Settings
import com.intellij.bigdatatools.awsBase.connection.auth.AuthenticationType
import com.intellij.bigdatatools.awsBase.settings.AwsCompatibleConnectionDataBase
import com.intellij.credentialStore.Credentials
import com.intellij.openapi.application.ApplicationManager
import com.jetbrains.bigdatatools.common.filestorages.BucketFilterType
import com.jetbrains.bigdatatools.common.settings.GlobalConnectionSettings
import org.com.jetbrains.bigdatatools.S3ConnectionGroup
import org.com.jetbrains.bigdatatools.aws.s3.S3ConnectionData

/**
 * Syncs the Big Data Tools connection once Local S3 is up, including after a restart caused by a settings change.
 */
class SyncLocalS3ConnectionListener : LocalS3StateListener {
    override fun started(endpoint: String) {
        // Reading and writing credentials goes through PasswordSafe, so keep it off the EDT.
        ApplicationManager.getApplication().executeOnPooledThread {
            LocalS3Connection.sync()
        }
    }
}

internal object LocalS3Connection {
    private const val LOCAL_S3_CONNECTION_ID = "com.github.linuxchina.locals3.connection"
    private const val LOCAL_S3_CONNECTION_NAME = "Local S3"

    /**
     * Creates the Local S3 connection, or recreates it when its endpoint, credentials or TLS flag
     * no longer match the current settings.
     */
    @Synchronized
    fun sync() {
        val connectionSettings = GlobalConnectionSettings.getInstance()
        val settings = LocalS3Settings.getInstance()
        val existingConnection = connectionSettings.getConnections().firstOrNull { it.innerId == LOCAL_S3_CONNECTION_ID }

        if (existingConnection is S3ConnectionData && existingConnection.matches(settings)) {
            return
        }
        if (existingConnection != null) {
            connectionSettings.removeConnection(LOCAL_S3_CONNECTION_ID)
        }
        connectionSettings.addConnection(createConnectionData(settings))
    }

    /**
     * Local S3 only verifies signatures when both keys are set, see `LocalS3Service.buildLocalS3()`.
     */
    private val LocalS3Settings.isAnonymous: Boolean
        get() = accessKeyId.isEmpty() || secretAccessKey.isEmpty()

    private fun S3ConnectionData.matches(settings: LocalS3Settings): Boolean {
        if (!useCustomEndpoint || customEndpoint != settings.endpoint || trustAllSsl != settings.tlsEnabled) {
            return false
        }
        if (settings.isAnonymous) {
            return activeAuthenticationType == AuthenticationType.ANON.id
        }
        val credentials = getCredentials(AwsCompatibleConnectionDataBase.SECRET_KEY_ID)
        return activeAuthenticationType == AuthenticationType.KEY_PAIR.id &&
                credentials?.userName == settings.accessKeyId &&
                credentials.getPasswordAsString() == settings.secretAccessKey
    }

    private fun createConnectionData(settings: LocalS3Settings) = S3ConnectionGroup().createBlankData(
        LOCAL_S3_CONNECTION_ID,
        false
    ).apply {
        name = LOCAL_S3_CONNECTION_NAME
        useCustomEndpoint = true
        customEndpoint = settings.endpoint
        customRegion = LocalS3Settings.REGION
        region = LocalS3Settings.REGION
        if (settings.isAnonymous) {
            activeAuthenticationType = AuthenticationType.ANON.id
        } else {
            activeAuthenticationType = AuthenticationType.KEY_PAIR.id
            setCredentials(
                Credentials(settings.accessKeyId, settings.secretAccessKey),
                AwsCompatibleConnectionDataBase.SECRET_KEY_ID
            )
        }
        profileName = "default"
        isEnabled = true
        isMultiBucketSupport = true
        isBucketSourceCustom = false
        bucketFilterType = BucketFilterType.CONTAINS
        bucketFilter = ""
        bucket = ""
        trustAllSsl = settings.tlsEnabled
    }
}
