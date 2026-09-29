package com.github.linuxchina.locals3jbplugin

import com.intellij.openapi.util.IconLoader
import com.intellij.ui.BadgeIconSupplier
import javax.swing.Icon

object LocalS3Icons {
    @JvmField
    val Bucket: Icon = IconLoader.getIcon("/icons/bucket-16.svg", LocalS3Icons::class.java)

    /** Local S3 is running, but was started by another process. */
    @JvmField
    val BucketShared: Icon = BadgeIconSupplier(Bucket).infoIcon

    /** Local S3 is not running. */
    @JvmField
    val BucketStopped: Icon = IconLoader.getDisabledIcon(Bucket)

    /** Local S3 failed to start or stopped unexpectedly. */
    @JvmField
    val BucketFailed: Icon = BadgeIconSupplier(BucketStopped).errorIcon
}
