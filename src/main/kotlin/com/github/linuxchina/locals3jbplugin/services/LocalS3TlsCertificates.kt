package com.github.linuxchina.locals3jbplugin.services

import com.intellij.openapi.diagnostic.thisLogger
import com.robothy.s3.rest.LocalS3Tls
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermissions
import java.time.Duration
import java.time.Instant

/**
 * The certificate that Local S3 serves HTTPS with, kept in [DEFAULT_DIRECTORY] so that it survives restarts and is shared
 * by all IDEs: a client that trusts it once keeps trusting it.
 *
 * A self-signed certificate is generated when there is none, and generated again when it is about to expire. A
 * certificate of the user, e.g. created by mkcert, replaces [CERTIFICATE_FILE] and [PRIVATE_KEY_FILE] and is never
 * overwritten.
 */
object LocalS3TlsCertificates {
    const val CERTIFICATE_FILE = "locals3.pem"
    const val PRIVATE_KEY_FILE = "locals3-key.pem"
    private const val LOCK_FILE = ".lock"

    /** A generated certificate is renewed when it expires within this period. */
    val RENEW_BEFORE_EXPIRY: Duration = Duration.ofDays(30)

    val DEFAULT_DIRECTORY: Path by lazy {
        Path.of(System.getProperty("user.home"), ".locals3", "tls")
    }

    /**
     * @throws IllegalArgumentException if the certificate or key files can't be read or don't belong together.
     */
    @Synchronized // A JVM can't lock the lock file twice.
    fun loadOrCreate(directory: Path = DEFAULT_DIRECTORY, now: Instant = Instant.now()): LocalS3Tls {
        Files.createDirectories(directory)
        // Several IDEs may start at the same time: don't let them pair the certificate of one with the key of another.
        FileChannel.open(directory.resolve(LOCK_FILE), StandardOpenOption.CREATE, StandardOpenOption.WRITE).use { channel ->
            channel.lock().use {
                val certFile = directory.resolve(CERTIFICATE_FILE)
                val keyFile = directory.resolve(PRIVATE_KEY_FILE)
                if (Files.isRegularFile(certFile) && Files.isRegularFile(keyFile)) {
                    val tls = LocalS3Tls.of(certFile.toString(), keyFile.toString())
                    if (!isGenerated(tls) || !expiresSoon(tls, now)) {
                        return tls
                    }
                    thisLogger().info("Renewing the Local S3 certificate that expires at ${tls.certificate().notAfter.toInstant()}")
                }
                return generate(certFile, keyFile)
            }
        }
    }

    /** Whether the certificate is one that [LocalS3Tls.selfSigned] generated rather than one of the user. */
    private fun isGenerated(tls: LocalS3Tls): Boolean =
        tls.isSelfSigned && tls.certificate().subjectX500Principal.name.contains("O=LocalS3")

    private fun expiresSoon(tls: LocalS3Tls, now: Instant): Boolean =
        tls.certificate().notAfter.toInstant().isBefore(now.plus(RENEW_BEFORE_EXPIRY))

    private fun generate(certFile: Path, keyFile: Path): LocalS3Tls {
        val tls = LocalS3Tls.selfSigned()
        // The key first: a certificate is only read together with its key, and both are replaced under the lock.
        write(keyFile, tls.privateKeyPem(), secret = true)
        write(certFile, tls.certificateChainPem(), secret = false)
        thisLogger().info("Generated the Local S3 certificate $certFile: ${tls.describe()}")
        return tls
    }

    private fun write(file: Path, content: String, secret: Boolean) {
        val temp = Files.createTempFile(file.parent, file.fileName.toString(), ".tmp")
        try {
            if (secret) {
                restrictToOwner(temp)
            }
            Files.writeString(temp, content)
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } finally {
            Files.deleteIfExists(temp)
        }
    }

    private fun restrictToOwner(file: Path) {
        try {
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"))
        } catch (e: UnsupportedOperationException) {
            // Windows: the file is in the user's home directory.
        }
    }
}
