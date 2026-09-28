import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.intellij.platform")
    id("org.jetbrains.changelog")
}

dependencies {
    implementation("io.github.robothy:local-s3-rest:2.5.0-SNAPSHOT") {
//        exclude(group = "io.netty", module = "netty-buffer")
//        exclude(group = "io.netty", module = "netty-handler")
//        exclude(group = "io.netty", module = "netty-transport")
//        exclude(group = "io.netty", module = "netty-codec-http")
    }
    testImplementation("junit:junit:4.13.2")

    // IntelliJ Platform Gradle Plugin Dependencies Extension - read more: https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin-dependencies-extension.html
    intellijPlatform {
        intellijIdea("2026.2")
        testFramework(TestFrameworkType.Platform)
//        plugin("com.intellij.bigdatatools", "262.8665.337")
        plugin("intellij.bigdatatools.awsBase", "262.8665.369")
        plugin("intellij.bigdatatools.gcloud", "262.8665.369")
        plugin("intellij.bigdatatools.azure", "262.8665.369")
        plugin("com.intellij.bigdatatools.core", "262.8665.337")
        plugin("intellij.bigdatatools.coreUi", "262.8665.173")
        plugin("com.intellij.bigdatatools.rfs", "262.8665.176")
        plugin("com.intellij.bigdatatools.binary.files", "262.8665.176")
    }
}
