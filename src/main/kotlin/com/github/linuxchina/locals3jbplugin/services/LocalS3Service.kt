package com.github.linuxchina.locals3jbplugin.services

import com.github.linuxchina.locals3jbplugin.settings.LocalS3Settings
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.robothy.s3.rest.LocalS3
import com.robothy.s3.rest.bootstrap.LocalS3Mode

@Service(Service.Level.APP)
class LocalS3Service : Disposable {
    var localS3: LocalS3? = null

    @Synchronized
    fun start() {
        stop()

        val settings = LocalS3Settings.getInstance()
        val dataDirectory = settings.ensureDataDirectory()
        val builder = LocalS3.builder()
            .bindHost(settings.host)
            .port(settings.port)
            .mode(LocalS3Mode.PERSISTENCE)
            .dataPath(dataDirectory.toString())
            .credentials(settings.accessKeyId, settings.secretAccessKey)
            .buckets(*settings.bucketNames.toTypedArray())
            .netty {
                it.daemonThreads(true)
            }

        if (settings.tlsEnabled) {
            builder.tls {
                it.selfSigned().required(true)
            }
        }

        localS3 = builder.build().also(LocalS3::start)
    }

    @Synchronized
    fun restart() {
        stop()
        start()
    }

    @Synchronized
    fun stop() {
        localS3?.let {
            if (it.isRunning) {
                it.close()
            }
            localS3 = null
        }
    }

    override fun dispose() {
        stop()
    }
}
