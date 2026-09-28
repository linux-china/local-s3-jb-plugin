package com.github.linuxchina.locals3jbplugin.services

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.robothy.s3.core.event.S3Change
import com.robothy.s3.core.event.S3ChangeListener
import com.robothy.s3.rest.LocalS3
import com.robothy.s3.rest.LocalS3Builder.WebsiteSettings
import com.robothy.s3.rest.bootstrap.LocalS3Mode

@Service(Service.Level.APP)
class LocalS3Service : Disposable {
    var localS3: LocalS3? = null

    @Synchronized
    fun start() {
        localS3 = LocalS3.builder()
            .mode(LocalS3Mode.IN_MEMORY)
            .credentials("admin", "admin")
            .buckets("demo1", "demo2", "demo3", "celld-bucket")
            .netty {
                it.daemonThreads(true)
            }
            .build()
        localS3!!.start()
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
