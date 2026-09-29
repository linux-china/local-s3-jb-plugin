package com.github.linuxchina.locals3jbplugin.services

import com.intellij.util.messages.Topic

/**
 * Notified when Local S3 becomes available, either started by [LocalS3Service] or shared with another process, so
 * integrations (e.g. Big Data Tools) can sync with its endpoint and credentials once it is actually up.
 */
fun interface LocalS3StateListener {
    fun started(endpoint: String)

    companion object {
        @Topic.AppLevel
        val TOPIC: Topic<LocalS3StateListener> = Topic(LocalS3StateListener::class.java)
    }
}
