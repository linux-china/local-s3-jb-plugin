package com.github.linuxchina.locals3jbplugin.services

import com.github.linuxchina.locals3jbplugin.settings.LocalS3Settings
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.io.delete
import com.robothy.s3.rest.LocalS3
import com.robothy.s3.rest.bootstrap.LocalS3Mode
import java.net.ServerSocket
import java.nio.file.Files
import java.nio.file.Path

class LocalS3ServiceTest : BasePlatformTestCase() {
    private lateinit var previousState: LocalS3Settings.SettingsState
    private lateinit var dataDirectory: Path
    private var otherLocalS3: LocalS3? = null

    override fun setUp() {
        super.setUp()
        // The test project's startup activity may have started Local S3 with the default settings.
        service<LocalS3Service>().stop()
        val settings = LocalS3Settings.getInstance()
        previousState = settings.state
        settings.loadState(LocalS3Settings.SettingsState())
        dataDirectory = Files.createTempDirectory("local-s3-service-test")
        settings.dataPath = dataDirectory.toString()
        settings.port = ServerSocket(0).use { it.localPort }
    }

    override fun tearDown() {
        try {
            service<LocalS3Service>().stop()
            otherLocalS3?.close()
            LocalS3Settings.getInstance().loadState(previousState)
            dataDirectory.delete()
        } finally {
            super.tearDown()
        }
    }

    fun testSharesRunningLocalS3AndTakesOverWhenItStops() {
        val localS3Service = service<LocalS3Service>()
        otherLocalS3 = startOtherLocalS3()

        assertTrue(localS3Service.start())
        assertTrue(localS3Service.isShared())
        assertFalse(localS3Service.isRunning())

        // Starting again while the shared server is up keeps sharing it.
        assertTrue(localS3Service.start())
        assertTrue(localS3Service.isShared())

        otherLocalS3!!.close()
        otherLocalS3 = null
        localS3Service.takeOverIfSharedServerGone()

        assertFalse(localS3Service.isShared())
        assertTrue(localS3Service.isRunning())
        assertTrue(LocalS3Service.isLocalS3Listening(LocalS3Settings.getInstance()))
    }

    fun testStartReplacesSharedLocalS3ThatHasStopped() {
        val localS3Service = service<LocalS3Service>()
        otherLocalS3 = startOtherLocalS3()
        assertTrue(localS3Service.start())
        assertTrue(localS3Service.isShared())

        otherLocalS3!!.close()
        otherLocalS3 = null

        assertTrue(localS3Service.start())
        assertFalse(localS3Service.isShared())
        assertTrue(localS3Service.isRunning())
    }

    fun testPublishesStartedOnlyWhenLocalS3BecomesAvailable() {
        val localS3Service = service<LocalS3Service>()
        val startedEndpoints = mutableListOf<String>()
        ApplicationManager.getApplication().messageBus.connect(testRootDisposable)
            .subscribe(LocalS3StateListener.TOPIC, LocalS3StateListener { startedEndpoints += it })
        val endpoint = LocalS3Settings.getInstance().endpoint

        assertTrue(localS3Service.start())
        assertEquals(listOf(endpoint), startedEndpoints)

        // Already running: nothing new to sync.
        assertTrue(localS3Service.start())
        assertEquals(listOf(endpoint), startedEndpoints)

        assertTrue(localS3Service.restart())
        assertEquals(listOf(endpoint, endpoint), startedEndpoints)
    }

    fun testPublishesStartedWhenSharingLocalS3() {
        val localS3Service = service<LocalS3Service>()
        val startedEndpoints = mutableListOf<String>()
        ApplicationManager.getApplication().messageBus.connect(testRootDisposable)
            .subscribe(LocalS3StateListener.TOPIC, LocalS3StateListener { startedEndpoints += it })
        otherLocalS3 = startOtherLocalS3()

        assertTrue(localS3Service.start())
        assertTrue(localS3Service.isShared())
        assertEquals(listOf(LocalS3Settings.getInstance().endpoint), startedEndpoints)
    }

    fun testPublishesStatusChanges() {
        val localS3Service = service<LocalS3Service>()
        val statuses = mutableListOf<LocalS3Status>()
        ApplicationManager.getApplication().messageBus.connect(testRootDisposable)
            .subscribe(LocalS3StateListener.TOPIC, object : LocalS3StateListener {
                override fun started(endpoint: String) = Unit
                override fun statusChanged(status: LocalS3Status) {
                    statuses += status
                }
            })
        assertEquals(LocalS3Status.STOPPED, localS3Service.status())

        assertTrue(localS3Service.start())
        assertEquals(LocalS3Status.RUNNING, localS3Service.status())
        localS3Service.stop()
        assertEquals(LocalS3Status.STOPPED, localS3Service.status())

        otherLocalS3 = startOtherLocalS3()
        assertTrue(localS3Service.start())
        assertEquals(LocalS3Status.SHARED, localS3Service.status())

        assertEquals(listOf(LocalS3Status.RUNNING, LocalS3Status.STOPPED, LocalS3Status.SHARED), statuses)
    }

    /**
     * Simulates a Local S3 started by another IDE on the configured port.
     */
    private fun startOtherLocalS3(): LocalS3 = LocalS3.builder()
        .bindHost(LocalS3Settings.DEFAULT_HOST)
        .port(LocalS3Settings.getInstance().port)
        .mode(LocalS3Mode.IN_MEMORY)
        .netty { it.daemonThreads(true) }
        .build()
        .also(LocalS3::start)
}
