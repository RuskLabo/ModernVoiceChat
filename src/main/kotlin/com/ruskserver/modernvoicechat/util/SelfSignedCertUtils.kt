package com.ruskserver.modernvoicechat.util

import org.slf4j.LoggerFactory
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileWriter
import java.io.FileInputStream
import java.nio.file.Path
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyFactory
import java.security.KeyStore
import java.security.SecureRandom
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.spec.PKCS8EncodedKeySpec
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.TimeZone
import java.util.concurrent.TimeUnit
import java.util.Base64

/**
 * 外部 BouncyCastle 依存を一切使用せず、
 * Netty OpenSSL / Java KeyManager 互換の X.509 v3 PEM 証明書および PKCS#8 秘密鍵を
 * 完全独立で安全生成するユーティリティ。
 */
object SelfSignedCertUtils {
    private val logger = LoggerFactory.getLogger(SelfSignedCertUtils::class.java)

    data class CertKeyPair(val keyFile: File, val certFile: File)

    fun loadOrCreate(directory: Path): CertKeyPair {
        val certDir = directory.toFile()
        check(certDir.exists() || certDir.mkdirs()) {
            "Could not create certificate directory: ${certDir.absolutePath}"
        }

        val keyFile = File(certDir, "key.pem")
        val certFile = File(certDir, "cert.pem")

        if (keyFile.isFile && certFile.isFile) {
            val pair = CertKeyPair(keyFile, certFile)
            try {
                val certificate = FileInputStream(certFile).use {
                    CertificateFactory.getInstance("X.509").generateCertificate(it)
                } as java.security.cert.X509Certificate
                certificate.checkValidity(Date(System.currentTimeMillis() + TimeUnit.DAYS.toMillis(30)))
                certificate.verify(certificate.publicKey)
                loadKeyStore(pair, "validation".toCharArray())
                return pair
            } catch (e: Exception) {
                logger.warn("Existing QUIC certificate is invalid or near expiry; rotating it: ${e.message}")
                keyFile.delete()
                certFile.delete()
            }
        }

        try {
            val keyGen = KeyPairGenerator.getInstance("RSA")
            keyGen.initialize(2048, SecureRandom())
            val keyPair = keyGen.generateKeyPair()

            val privateKeyPem = buildString {
                appendLine("-----BEGIN PRIVATE KEY-----")
                appendLine(Base64.getMimeEncoder(64, byteArrayOf('\n'.code.toByte())).encodeToString(keyPair.private.encoded))
                appendLine("-----END PRIVATE KEY-----")
            }

            val certBytes = generateSelfSignedCertBytes(keyPair)
            val certPem = buildString {
                appendLine("-----BEGIN CERTIFICATE-----")
                appendLine(Base64.getMimeEncoder(64, byteArrayOf('\n'.code.toByte())).encodeToString(certBytes))
                appendLine("-----END CERTIFICATE-----")
            }

            FileWriter(keyFile).use { it.write(privateKeyPem) }
            FileWriter(certFile).use { it.write(certPem) }
            keyFile.setReadable(false, false)
            keyFile.setWritable(false, false)
            keyFile.setReadable(true, true)
            keyFile.setWritable(true, true)

            logger.info("Created persistent X.509 certificate for QUIC server at ${certDir.absolutePath}")
            return CertKeyPair(keyFile, certFile)
        } catch (e: Exception) {
            logger.error("Failed to generate standalone self-signed certificate", e)
            throw e
        }
    }

    @Deprecated("Use loadOrCreate with a persistent directory")
    fun generateSelfSignedCert(): CertKeyPair =
        loadOrCreate(Path.of(System.getProperty("java.io.tmpdir"), "modernvoicechat_certs"))

    fun loadKeyStore(pair: CertKeyPair, password: CharArray): KeyStore {
        val keyBytes = decodePem(pair.keyFile, "PRIVATE KEY")
        val privateKey = KeyFactory.getInstance("RSA")
            .generatePrivate(PKCS8EncodedKeySpec(keyBytes))
        val certificate = FileInputStream(pair.certFile).use {
            CertificateFactory.getInstance("X.509").generateCertificate(it)
        }
        return KeyStore.getInstance("PKCS12").apply {
            load(null, password)
            setKeyEntry("modernvoicechat", privateKey, password, arrayOf(certificate))
        }
    }

    fun certificateFingerprint(pair: CertKeyPair): ByteArray {
        val certificate = FileInputStream(pair.certFile).use {
            CertificateFactory.getInstance("X.509").generateCertificate(it)
        }
        return MessageDigest.getInstance("SHA-256").digest(certificate.encoded)
    }

    private fun decodePem(file: File, type: String): ByteArray {
        val content = file.readText()
            .replace("-----BEGIN $type-----", "")
            .replace("-----END $type-----", "")
            .replace("\\s".toRegex(), "")
        return Base64.getDecoder().decode(content)
    }

    private fun generateSelfSignedCertBytes(keyPair: KeyPair): ByteArray {
        val pubKey = keyPair.public.encoded
        val sig = Signature.getInstance("SHA256withRSA")
        sig.initSign(keyPair.private)

        // DER Encoded CertificateInfo
        val tbsStream = ByteArrayOutputStream()
        
        // Version v3: [0] -> INTEGER 2
        tbsStream.write(byteArrayOf(0xa0.toByte(), 0x03, 0x02, 0x01, 0x02))
        // Serial Number: INTEGER 1
        tbsStream.write(byteArrayOf(0x02, 0x01, 0x01))
        // Signature Algorithm OID (SHA256withRSA: 1.2.840.113549.1.1.11)
        tbsStream.write(byteArrayOf(0x30, 0x0d, 0x06, 0x09, 0x2a, 0x86.toByte(), 0x48, 0x86.toByte(), 0xf7.toByte(), 0x0d, 0x01, 0x01, 0x0b, 0x05, 0x00))
        // Issuer: RDN Sequence (CN=ModernVoiceChat)
        tbsStream.write(byteArrayOf(
            0x30, 0x1d, 0x31, 0x1b, 0x30, 0x19, 0x06, 0x03, 0x55, 0x04, 0x03, 0x0c, 0x12,
            'M'.code.toByte(), 'o'.code.toByte(), 'd'.code.toByte(), 'e'.code.toByte(), 'r'.code.toByte(), 'n'.code.toByte(),
            'V'.code.toByte(), 'o'.code.toByte(), 'i'.code.toByte(), 'c'.code.toByte(), 'e'.code.toByte(), 'C'.code.toByte(),
            'h'.code.toByte(), 'a'.code.toByte(), 't'.code.toByte(), 'S'.code.toByte(), 'r'.code.toByte(), 'v'.code.toByte()
        ))
        val notBefore = Date(System.currentTimeMillis() - TimeUnit.DAYS.toMillis(1))
        val notAfter = Date(System.currentTimeMillis() + TimeUnit.DAYS.toMillis(3650))
        val validityContent = derUtcTime(notBefore) + derUtcTime(notAfter)
        tbsStream.write(byteArrayOf(0x30, validityContent.size.toByte()))
        tbsStream.write(validityContent)
        // Subject: RDN Sequence (CN=ModernVoiceChat)
        tbsStream.write(byteArrayOf(
            0x30, 0x1d, 0x31, 0x1b, 0x30, 0x19, 0x06, 0x03, 0x55, 0x04, 0x03, 0x0c, 0x12,
            'M'.code.toByte(), 'o'.code.toByte(), 'd'.code.toByte(), 'e'.code.toByte(), 'r'.code.toByte(), 'n'.code.toByte(),
            'V'.code.toByte(), 'o'.code.toByte(), 'i'.code.toByte(), 'c'.code.toByte(), 'e'.code.toByte(), 'C'.code.toByte(),
            'h'.code.toByte(), 'a'.code.toByte(), 't'.code.toByte(), 'S'.code.toByte(), 'r'.code.toByte(), 'v'.code.toByte()
        ))
        // SubjectPublicKeyInfo
        tbsStream.write(pubKey)

        val tbsBytes = tbsStream.toByteArray()
        val tbsHeader = ByteArrayOutputStream().apply {
            write(0x30)
            writeDerLength(tbsBytes.size, this)
            write(tbsBytes)
        }.toByteArray()

        sig.update(tbsHeader)
        val signatureBytes = sig.sign()

        val certContent = ByteArrayOutputStream().apply {
            write(tbsHeader)
            // Signature Algorithm
            write(byteArrayOf(0x30, 0x0d, 0x06, 0x09, 0x2a, 0x86.toByte(), 0x48, 0x86.toByte(), 0xf7.toByte(), 0x0d, 0x01, 0x01, 0x0b, 0x05, 0x00))
            // Signature Value BitString
            write(0x03)
            writeDerLength(signatureBytes.size + 1, this)
            write(0x00)
            write(signatureBytes)
        }.toByteArray()

        return ByteArrayOutputStream().apply {
            write(0x30)
            writeDerLength(certContent.size, this)
            write(certContent)
        }.toByteArray()
    }

    private fun writeDerLength(length: Int, stream: ByteArrayOutputStream) {
        if (length < 128) {
            stream.write(length)
        } else if (length < 256) {
            stream.write(0x81)
            stream.write(length)
        } else {
            stream.write(0x82)
            stream.write((length shr 8) and 0xFF)
            stream.write(length and 0xFF)
        }
    }

    private fun derUtcTime(date: Date): ByteArray {
        val formatter = SimpleDateFormat("yyMMddHHmmss'Z'").apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        val value = formatter.format(date).toByteArray(Charsets.US_ASCII)
        return byteArrayOf(0x17, value.size.toByte()) + value
    }
}
