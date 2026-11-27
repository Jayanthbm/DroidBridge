package com.jayanth.droidbridge.network

import android.content.Context
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.BasicConstraints
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.asn1.x509.KeyUsage
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import java.util.Date
import javax.net.ssl.KeyManager
import javax.net.ssl.KeyManagerFactory

data class AndroidIdentity(
    val keyManagers: Array<KeyManager>,
    val certificate: X509Certificate,
    val fingerprint: String,
)

object AndroidIdentityStore {
    private const val PASSWORD = "droidbridge-local-identity"
    private const val ALIAS = "identity"
    private const val FILE_NAME = "identity.p12"

    @Synchronized
    fun loadOrCreate(context: Context): AndroidIdentity {
        val file = File(context.filesDir, FILE_NAME)
        return try {
            if (!file.exists()) generate(file)
            load(file)
        } catch (_: Exception) {
            file.delete()
            generate(file)
            load(file)
        }
    }

    private fun load(file: File): AndroidIdentity {
        val keyStore = KeyStore.getInstance("PKCS12")
        FileInputStream(file).use { keyStore.load(it, PASSWORD.toCharArray()) }
        val certificate = keyStore.getCertificate(ALIAS) as? X509Certificate
            ?: error("TLS certificate missing")
        keyStore.getKey(ALIAS, PASSWORD.toCharArray()) as? PrivateKey
            ?: error("TLS private key missing")
        certificate.checkValidity()

        val factory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        factory.init(keyStore, PASSWORD.toCharArray())
        return AndroidIdentity(
            keyManagers = factory.keyManagers,
            certificate = certificate,
            fingerprint = WireProtocol.fingerprint(certificate.encoded),
        )
    }

    private fun generate(file: File) {
        val provider = BouncyCastleProvider()
        val generator = KeyPairGenerator.getInstance("EC", provider)
        generator.initialize(ECGenParameterSpec("secp256r1"), SecureRandom())
        val keyPair = generator.generateKeyPair()

        val now = System.currentTimeMillis()
        val subject = X500Name("CN=DroidBridge Android,O=DroidBridge")
        val builder = JcaX509v3CertificateBuilder(
            subject,
            BigInteger(64, SecureRandom()).abs(),
            Date(now - 5 * 60 * 1000L),
            Date(now + 10L * 365 * 24 * 60 * 60 * 1000),
            subject,
            keyPair.public,
        )
        builder.addExtension(Extension.basicConstraints, true, BasicConstraints(false))
        builder.addExtension(
            Extension.keyUsage,
            true,
            KeyUsage(KeyUsage.digitalSignature or KeyUsage.keyEncipherment),
        )

        val signer = JcaContentSignerBuilder("SHA256withECDSA").setProvider(provider).build(keyPair.private)
        val certificate = JcaX509CertificateConverter().setProvider(provider).getCertificate(builder.build(signer))

        val keyStore = KeyStore.getInstance("PKCS12")
        keyStore.load(null, PASSWORD.toCharArray())
        keyStore.setKeyEntry(ALIAS, keyPair.private, PASSWORD.toCharArray(), arrayOf(certificate))
        file.parentFile?.mkdirs()
        FileOutputStream(file).use { keyStore.store(it, PASSWORD.toCharArray()) }
    }
}
