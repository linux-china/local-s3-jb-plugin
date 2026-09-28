package com.github.linuxchina.locals3jbplugin.startup

import com.github.linuxchina.locals3jbplugin.services.LocalS3Service
import com.intellij.ide.AppLifecycleListener
import com.intellij.openapi.components.service

class LocalS3LifecycleListener : AppLifecycleListener {

    override fun appStarted() {
        service<LocalS3Service>().start()
    }

    override fun appWillBeClosed(isRestart: Boolean) {
        service<LocalS3Service>().stop()
    }
}