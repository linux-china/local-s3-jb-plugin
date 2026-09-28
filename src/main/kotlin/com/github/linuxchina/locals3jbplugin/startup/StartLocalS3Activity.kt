package com.github.linuxchina.locals3jbplugin.startup

import com.github.linuxchina.locals3jbplugin.services.LocalS3Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity

class StartLocalS3Activity : ProjectActivity {
    override suspend fun execute(project: Project) {
        if (!service<LocalS3Service>().isRunning()) {
            service<LocalS3Service>().start()
        }
    }
}