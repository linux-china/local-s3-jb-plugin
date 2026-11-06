import org.jetbrains.changelog.Changelog
import org.jetbrains.changelog.markdownToHTML
import org.jetbrains.intellij.platform.gradle.TestFrameworkType


fun properties(key: String) = project.findProperty(key).toString()

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.intellij.platform")
    id("org.jetbrains.changelog")
}

kotlin {
    jvmToolchain(25)
}

dependencies {
    implementation("org.mvnsearch:local-s3-rest:2.5.0-SNAPSHOT") {
        exclude(group = "org.jspecify", module = "jspecify")
        // exclude netty dependencies because bundled with JetBrains platform
        // exclude(group = "io.netty", module = "netty-transport")
        // exclude(group = "io.netty", module = "netty-codec-http")
        // exclude(group = "io.netty", module = "netty-handler")
    }
    // Bundle SLF4J 2.x with the JUL provider (same as the IDE platform, so logs go to idea.log).
    // Big Data Tools RFS (parent classloader via optional depends) ships SLF4J 1.7 + reload4j inside
    // apache.hadoop.client.jar, whose StaticLoggerBinder fails to initialize and breaks LocalS3.<clinit>.
    implementation("org.slf4j:slf4j-api:2.0.19")
    implementation("org.slf4j:slf4j-jdk14:2.0.19")
    testImplementation("junit:junit:4.13.2")

    // IntelliJ Platform Gradle Plugin Dependencies Extension - read more: https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin-dependencies-extension.html
    intellijPlatform {
        intellijIdea("2026.3")
        bundledPlugin("com.intellij.modules.json")
        testFramework(TestFrameworkType.Platform)
        plugin("com.intellij.bigdatatools.core", "263.7307.200")
        plugin("com.intellij.bigdatatools.rfs", "263.7307.200")

    }
}

tasks {
    // In the test sandbox, the bundled Swagger plugin fails on mixed Jackson versions as soon as JSON Schemas are used.
    prepareTestSandbox {
        disabledPlugins.add("com.intellij.swagger")
    }

    buildSearchableOptions {
        enabled = false
    }

    patchPluginXml {
        version = properties("version")
        sinceBuild.set(properties("pluginSinceBuild"))
        untilBuild.set(properties("pluginUntilBuild"))

        // Extract the <!-- Plugin description --> section from README.md and provide for the plugin's manifest
        pluginDescription.set(
            projectDir.resolve("README.md").readText().lines().run {
                val start = "<!-- Plugin description -->"
                val end = "<!-- Plugin description end -->"

                if (!containsAll(listOf(start, end))) {
                    throw GradleException("Plugin description section not found in README.md:\n$start ... $end")
                }
                subList(indexOf(start) + 1, indexOf(end))
            }.joinToString("\n").run { markdownToHTML(this) }
        )

        // Get the latest available change notes from the changelog file
        changeNotes.set(provider {
            with(changelog) {
                renderItem(
                    getOrNull(properties("version")) ?: getLatest(),
                    Changelog.OutputType.HTML,
                )
            }
        })
    }
}
