package com.github.linuxchina.locals3jbplugin.startup

import com.github.linuxchina.locals3jbplugin.project.LocalS3ProjectSetup
import com.github.linuxchina.locals3jbplugin.services.LocalS3Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class StartLocalS3Activity : ProjectActivity {
    override suspend fun execute(project: Project) {
        // Starting probes the port and binds Netty under the service lock, which blocks: keep it off the default
        // dispatcher, so that projects opened concurrently don't starve it while waiting for each other.
        withContext(Dispatchers.IO) {
            service<LocalS3Service>().autoStart(project)
            // Creates the buckets and imports the seed data of .locals3.json, now and whenever Local S3 starts again.
            project.service<LocalS3ProjectSetup>().applyAndSubscribe()
        }
    }
}
