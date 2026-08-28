package dev.artzig

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.lang.reflect.Method
import java.security.InvalidAlgorithmParameterException
import java.security.Key
import java.security.KeyStore
import java.security.KeyStoreException
import java.security.KeyStoreSpi
import java.security.NoSuchAlgorithmException
import java.security.Provider
import java.security.ProviderException
import java.security.SecureRandom
import java.security.UnrecoverableKeyException
import java.security.cert.Certificate
import java.security.cert.CertificateException
import java.security.spec.AlgorithmParameterSpec
import java.util.Base64
import java.util.Collections
import java.util.Date
import java.util.Enumeration
import java.util.LinkedHashMap
import java.util.Properties
import javax.crypto.KeyGeneratorSpi
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

/** A small persistent AndroidKeyStore compatibility provider for hosted APKs. */
public final class ArtzigKeyStoreProvider : Provider {
    public constructor () : super(TYPE, 1.0, "artzig host-backed AndroidKeyStore") {

        put("KeyStore." + TYPE, Store::class.java.getName())
        put("KeyGenerator.AES", Generator::class.java.getName())
    }

    public final class Store : KeyStoreSpi {
        public constructor () {}

        @Throws(UnrecoverableKeyException::class)
        public override fun engineGetKey(
            alias: String,
            password: CharArray?,
        ): Key? {
            try {
                return getKey(alias)
            } catch (e: IOException) {
                throw UnrecoverableKeyException(e.toString())
            }
        }

        public override fun engineGetCertificateChain(alias: String): Array<Certificate>? = null

        public override fun engineGetCertificate(alias: String): Certificate? = null

        public override fun engineGetCreationDate(alias: String): Date? = if (engineContainsAlias(alias)) Date(0) else null

        @Throws(KeyStoreException::class)
        public override fun engineSetKeyEntry(
            alias: String,
            key: Key,
            password: CharArray?,
            chain: Array<Certificate>?,
        ) {
            if (!(key is SecretKey) || key.getEncoded() == null) throw KeyStoreException("secret keys only")
            try {
                putKey(alias, key as SecretKey)
            } catch (e: IOException) {
                throw KeyStoreException(e)
            }
        }

        @Throws(KeyStoreException::class)
        public override fun engineSetKeyEntry(
            alias: String,
            key: ByteArray,
            chain: Array<Certificate>?,
        ) {
            try {
                putKey(alias, SecretKeySpec(key, "AES"))
            } catch (e: IOException) {
                throw KeyStoreException(e)
            }
        }

        @Throws(KeyStoreException::class)
        public override fun engineSetCertificateEntry(
            alias: String,
            cert: Certificate,
        ): Unit = throw KeyStoreException("certificates are unsupported")

        @Throws(KeyStoreException::class)
        public override fun engineDeleteEntry(alias: String) {
            try {
                loadKeys()
                KEYS.remove(alias)
                saveKeys()
            } catch (e: IOException) {
                throw KeyStoreException(e)
            }
        }

        public override fun engineAliases(): Enumeration<String> {
            try {
                loadKeys()
            } catch (ignored: IOException) {
            }
            return Collections.enumeration(KEYS.keys)
        }

        public override fun engineContainsAlias(alias: String): Boolean {
            try {
                return getKey(alias) != null
            } catch (e: IOException) {
                return false
            }
        }

        public override fun engineSize(): Int {
            try {
                loadKeys()
            } catch (ignored: IOException) {
            }
            return KEYS.size
        }

        public override fun engineIsKeyEntry(alias: String): Boolean = engineContainsAlias(alias)

        public override fun engineIsCertificateEntry(alias: String): Boolean = false

        public override fun engineGetCertificateAlias(cert: Certificate): String? = null

        @Throws(IOException::class, NoSuchAlgorithmException::class, CertificateException::class)
        public override fun engineStore(
            stream: OutputStream,
            password: CharArray?,
        ) {
            saveKeys()
        }

        @Throws(IOException::class, NoSuchAlgorithmException::class, CertificateException::class)
        public override fun engineLoad(
            stream: InputStream?,
            password: CharArray?,
        ) {
            loadKeys()
        }

        @Throws(IOException::class, NoSuchAlgorithmException::class, CertificateException::class)
        public override fun engineLoad(parameter: KeyStore.LoadStoreParameter?) {
            loadKeys()
        }
    }

    public final class Generator : KeyGeneratorSpi {
        private var random: SecureRandom = SecureRandom()
        private var alias: String? = null
        private var keySize: Int = 256

        public constructor () {}

        protected override fun engineInit(random: SecureRandom?) {
            if (random != null) this.random = random
        }

        protected override fun engineInit(
            keySize: Int,
            random: SecureRandom?,
        ) {
            this.keySize = keySize
            if (random != null) this.random = random
        }

        @Throws(InvalidAlgorithmParameterException::class)
        protected override fun engineInit(
            params: AlgorithmParameterSpec?,
            random: SecureRandom?,
        ) {
            if (params == null) throw InvalidAlgorithmParameterException("missing KeyGenParameterSpec")
            if (random != null) this.random = random
            try {
                val getAlias: Method = params.javaClass.getMethod("getKeystoreAlias")
                alias = getAlias.invoke(params) as String
                try {
                    val requested: Int = (params.javaClass.getMethod("getKeySize").invoke(params) as Number).toInt()
                    if (requested > 0) keySize = requested
                } catch (ignored: ReflectiveOperationException) {
                }
            } catch (e: ReflectiveOperationException) {
                throw InvalidAlgorithmParameterException("unsupported parameters: " + params.javaClass, e)
            } catch (e: ClassCastException) {
                throw InvalidAlgorithmParameterException("unsupported parameters: " + params.javaClass, e)
            }
        }

        protected override fun engineGenerateKey(): SecretKey {
            val keyAlias = alias
            if (keyAlias == null || keyAlias.isEmpty()) throw ProviderException("key alias was not initialized")
            val encoded: ByteArray = ByteArray(Math.max(128, keySize) / 8)
            random.nextBytes(encoded)
            val key: SecretKey = SecretKeySpec(encoded, "AES")
            try {
                putKey(keyAlias, key)
            } catch (e: IOException) {
                throw ProviderException(e)
            }
            return key
        }
    }

    companion object {
        private val TYPE: String = "AndroidKeyStore"
        private val KEYS: MutableMap<String, SecretKey> = LinkedHashMap()
        private var loaded: Boolean = false

        @JvmStatic private fun backingFile(): File {
            var data: String? = System.getenv("ANDROID_DATA")
            if (data == null || data.isEmpty()) data = System.getProperty("java.io.tmpdir", "/tmp")
            return File(data, "artzig-keystore.properties")
        }

        @Throws(IOException::class)
        @Synchronized
        @JvmStatic
        private fun loadKeys() {
            if (loaded) return
            loaded = true
            val file: File = backingFile()
            if (!file.isFile()) return
            val properties: Properties = Properties()
            FileInputStream(file).use { `in`: InputStream ->
                properties.load(`in`)
            }
            for (alias: String in properties.stringPropertyNames()) {
                try {
                    val encoded: ByteArray = Base64.getDecoder().decode(properties.getProperty(alias))
                    KEYS.put(alias, SecretKeySpec(encoded, "AES"))
                } catch (ignored: IllegalArgumentException) {
                    // Ignore a damaged entry while leaving other aliases usable.
                }
            }
        }

        @Throws(IOException::class)
        @Synchronized
        @JvmStatic
        private fun saveKeys() {
            val file: File = backingFile()
            val parent: File? = file.getParentFile()
            if (parent != null && !parent.isDirectory() && !parent.mkdirs() && !parent.isDirectory()) {
                throw IOException("cannot create " + parent)
            }
            val properties: Properties = Properties()
            for (entry: MutableMap.MutableEntry<String, SecretKey> in KEYS.entries) {
                val encoded: ByteArray? = entry.value.getEncoded()
                if (encoded != null) properties.setProperty(entry.key, Base64.getEncoder().encodeToString(encoded))
            }
            val temporary: File = File(file.getPath() + ".tmp")
            FileOutputStream(temporary).use { out: OutputStream ->
                properties.store(out, "artzig AndroidKeyStore compatibility keys")
            }
            if (!temporary.renameTo(file)) {
                FileInputStream(temporary).use { `in`: InputStream ->
                    ; FileOutputStream(file).use { out: OutputStream ->
                        val buffer: ByteArray = ByteArray(4096)
                        run {
                            var n: Int
                            while ((`in`.read(buffer).also { n = it }) >= 0) out.write(buffer, 0, n)
                        }
                    }
                }
                temporary.delete()
            }
            file.setReadable(false, false)
            file.setWritable(false, false)
            file.setReadable(true, true)
            file.setWritable(true, true)
        }

        @Throws(IOException::class)
        @Synchronized
        @JvmStatic
        private fun getKey(alias: String): SecretKey? {
            loadKeys()
            return KEYS.get(alias)
        }

        @Throws(IOException::class)
        @Synchronized
        @JvmStatic
        private fun putKey(
            alias: String,
            key: SecretKey,
        ) {
            loadKeys()
            KEYS.put(alias, key)
            saveKeys()
        }
    }
}
