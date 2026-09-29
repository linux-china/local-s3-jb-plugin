package com.github.linuxchina.locals3jbplugin.services

import com.github.linuxchina.locals3jbplugin.LocalS3Bundle

enum class LocalS3Status(private val messageKey: String) {
    /** Started by this IDE. */
    RUNNING("status.running"),

    /** Started by another process, e.g. another IDE, and reused. */
    SHARED("status.shared"),
    STOPPED("status.stopped"),
    FAILED("status.failed");

    /** Whether clients can reach Local S3 at the configured endpoint. */
    val isAvailable: Boolean
        get() = this == RUNNING || this == SHARED

    val displayName: String
        get() = LocalS3Bundle.message(messageKey)
}
