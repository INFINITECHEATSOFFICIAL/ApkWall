package com.krishna.apkguard

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.android.apksig.ApkSigner
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.SecureRandom
import java.security.Signature
import java.security.Security
import java.security.cert.X509Certificate
import java.util.Date
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * A persistent app-local APK signing identity. The PKCS12 payload and its random password are
 * encrypted together with an AES-GCM key held by AndroidKeyStore. The private signing key is
 * never sent to a server or written in plaintext to shared storage.
 */
object LocalSigningIdentity {
    private const val FILE_NAME = "apkguard-signing-identity.bin"
    private const val WRAPPING_KEY_ALIAS = "krishna.apkguard.identity.wrap.v1"
    private const val PKCS12_ALIAS = "krishna-managed"
    private const val MAGIC = 0x4B414731 // KAG1
    private const val WRAP_TRANSFORMATION = "AES/GCM/NoPadding"
    private val lock = Any()

    data class Identity(val alias: String, val password: String, val pkcs12: ByteArray)

    fun hasIdentity(context: Context): Boolean = identityFile(context).isFile

    fun getOrCreate(context: Context): Identity = synchronized(lock) {
        if (identityFile(context).isFile) return@synchronized readIdentity(context)
        ensureBouncyCastle()
        val password = randomPassword()
        val bytes = generatePkcs12(PKCS12_ALIAS, password)
        writeEncryptedIdentity(context, PKCS12_ALIAS, password, bytes)
        Identity(PKCS12_ALIAS, password, bytes)
    }

    fun load(context: Context): Identity = synchronized(lock) {
        readIdentity(context)
    }

    fun exportBackup(context: Context, backupPassword: String): ByteArray {
        require(backupPassword.length >= 12) { "Use a backup password of at least 12 characters" }
        ensureBouncyCastle()
        val identity = load(context)
        val current = KeyStore.getInstance("PKCS12", "BC")
        current.load(ByteArrayInputStream(identity.pkcs12), identity.password.toCharArray())
        val privateKey = current.getKey(identity.alias, identity.password.toCharArray())
        val certificateChain = current.getCertificateChain(identity.alias)
            ?: throw IllegalStateException("Signing certificate chain is missing")
        val backup = KeyStore.getInstance("PKCS12", "BC")
        val password = backupPassword.toCharArray()
        backup.load(null, password)
        backup.setKeyEntry(identity.alias, privateKey, password, certificateChain)
        return ByteArrayOutputStream().use { out ->
            backup.store(out, password)
            out.toByteArray()
        }
    }

    private fun readIdentity(context: Context): Identity {
        ensureBouncyCastle()
        val file = identityFile(context)
        if (!file.isFile) throw IllegalStateException("The app signing identity has not been created yet")
        val iv: ByteArray
        val ciphertext: ByteArray
        DataInputStream(FileInputStream(file)).use { input ->
            if (input.readInt() != MAGIC) throw IllegalStateException("Stored signing identity has an invalid header")
            val ivSize = input.readInt()
            if (ivSize !in 12..16) throw IllegalStateException("Stored signing identity has an invalid nonce")
            iv = ByteArray(ivSize).also(input::readFully)
            ciphertext = input.readBytes()
        }
        val cipher = Cipher.getInstance(WRAP_TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, wrappingKey(context), GCMParameterSpec(128, iv))
        val plaintext = cipher.doFinal(ciphertext)
        DataInputStream(ByteArrayInputStream(plaintext)).use { input ->
            val alias = input.readUTF()
            val password = input.readUTF()
            val size = input.readInt()
            if (size <= 0 || size > plaintext.size) throw IllegalStateException("Stored signing key data is invalid")
            val pkcs12 = ByteArray(size).also(input::readFully)
            return Identity(alias, password, pkcs12)
        }
    }

    private fun writeEncryptedIdentity(context: Context, alias: String, password: String, pkcs12: ByteArray) {
        val payloadBytes = ByteArrayOutputStream().use { bytes ->
            DataOutputStream(bytes).use { data ->
                data.writeUTF(alias)
                data.writeUTF(password)
                data.writeInt(pkcs12.size)
                data.write(pkcs12)
            }
            bytes.toByteArray()
        }
        val cipher = Cipher.getInstance(WRAP_TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, wrappingKey(context))
        val encrypted = cipher.doFinal(payloadBytes)
        val temporary = File(context.filesDir, "$FILE_NAME.tmp")
        val destination = identityFile(context)
        DataOutputStream(FileOutputStream(temporary)).use { output ->
            output.writeInt(MAGIC)
            output.writeInt(cipher.iv.size)
            output.write(cipher.iv)
            output.write(encrypted)
            output.flush()
        }
        if (!temporary.renameTo(destination)) {
            temporary.delete()
            throw IllegalStateException("Could not save the local signing identity")
        }
    }

    private fun wrappingKey(context: Context): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val existing = store.getKey(WRAPPING_KEY_ALIAS, null) as? SecretKey
        if (existing != null) return existing
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                WRAPPING_KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setKeySize(256)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }

    private fun generatePkcs12(alias: String, password: String): ByteArray {
        val random = SecureRandom()
        val pairGenerator = KeyPairGenerator.getInstance("RSA", "BC")
        pairGenerator.initialize(3072, random)
        val pair = pairGenerator.generateKeyPair()
        val now = System.currentTimeMillis()
        val subject = X500Name("CN=Krishna APK Guard, O=Krishna, C=IN")
        val serial = BigInteger(160, random).abs().add(BigInteger.ONE)
        val certBuilder = JcaX509v3CertificateBuilder(
            subject,
            serial,
            Date(now - 60_000L),
            Date(now + 1000L * 60 * 60 * 24 * 3650),
            subject,
            pair.public
        )
        val signer = JcaContentSignerBuilder("SHA256withRSA").setProvider("BC").build(pair.private)
        val certificate = JcaX509CertificateConverter().setProvider("BC").getCertificate(certBuilder.build(signer))
        certificate.verify(pair.public)
        val passwordChars = password.toCharArray()
        val store = KeyStore.getInstance("PKCS12", "BC")
        store.load(null, passwordChars)
        store.setKeyEntry(alias, pair.private, passwordChars, arrayOf<X509Certificate>(certificate))
        return ByteArrayOutputStream().use { output ->
            store.store(output, passwordChars)
            output.toByteArray()
        }
    }

    private fun randomPassword(): String {
        val bytes = ByteArray(32).also(SecureRandom()::nextBytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    private fun identityFile(context: Context) = File(context.filesDir, FILE_NAME)

    fun ensureBouncyCastle() {
        val usable = try {
            Signature.getInstance("SHA256withRSA", "BC")
            true
        } catch (_: Exception) {
            false
        }
        if (!usable) {
            Security.removeProvider("BC")
            Security.insertProviderAt(BouncyCastleProvider(), 1)
        }
        try {
            Signature.getInstance("SHA256withRSA", "BC")
        } catch (e: Exception) {
            throw IllegalStateException("Bundled signing provider does not support SHA256withRSA", e)
        }
    }
}
