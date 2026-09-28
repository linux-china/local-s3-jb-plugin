package com.github.linuxchina.locals3jbplugin.settings

import com.intellij.util.messages.Topic

/**
 * Notified after Local S3 settings have been applied, so integrations (e.g. Big Data Tools)
 * can refresh anything derived from the endpoint or credentials.
 */
fun interface LocalS3SettingsListener {
    fun settingsChanged()

    companion object {
        @Topic.AppLevel
        val TOPIC: Topic<LocalS3SettingsListener> = Topic(LocalS3SettingsListener::class.java)
    }
}
