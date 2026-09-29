package com.github.linuxchina.locals3jbplugin.project

import com.github.linuxchina.locals3jbplugin.LocalS3Icons
import com.intellij.ide.FileIconProvider
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import javax.swing.Icon

/**
 * Shows [LocalS3ProjectConfig.FILE_NAME] with the Local S3 bucket icon.
 */
class LocalS3ProjectConfigIconProvider : FileIconProvider {
    override fun getIcon(file: VirtualFile, flags: Int, project: Project?): Icon? =
        LocalS3Icons.Bucket.takeIf { file.name == LocalS3ProjectConfig.FILE_NAME && !file.isDirectory }
}
