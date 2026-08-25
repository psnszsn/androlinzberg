package dev.artzig;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.security.InvalidAlgorithmParameterException;
import java.security.Key;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.KeyStoreSpi;
import java.security.NoSuchAlgorithmException;
import java.security.Provider;
import java.security.ProviderException;
import java.security.SecureRandom;
import java.security.UnrecoverableKeyException;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.spec.AlgorithmParameterSpec;
import java.util.Base64;
import java.util.Collections;
import java.util.Date;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;

import javax.crypto.KeyGeneratorSpi;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

/** A small persistent AndroidKeyStore compatibility provider for hosted APKs. */
public final class ArtzigKeyStoreProvider extends Provider {
    private static final String TYPE = "AndroidKeyStore";
    private static final Map<String, SecretKey> KEYS = new LinkedHashMap<>();
    private static boolean loaded;

    public ArtzigKeyStoreProvider() {
        super(TYPE, 1.0, "artzig host-backed AndroidKeyStore");
        put("KeyStore." + TYPE, Store.class.getName());
        put("KeyGenerator.AES", Generator.class.getName());
    }

    private static File backingFile() {
        String data = System.getenv("ANDROID_DATA");
        if (data == null || data.isEmpty()) data = System.getProperty("java.io.tmpdir", "/tmp");
        return new File(data, "artzig-keystore.properties");
    }

    private static synchronized void loadKeys() throws IOException {
        if (loaded) return;
        loaded = true;
        File file = backingFile();
        if (!file.isFile()) return;
        Properties properties = new Properties();
        try (InputStream in = new FileInputStream(file)) {
            properties.load(in);
        }
        for (String alias : properties.stringPropertyNames()) {
            try {
                byte[] encoded = Base64.getDecoder().decode(properties.getProperty(alias));
                KEYS.put(alias, new SecretKeySpec(encoded, "AES"));
            } catch (IllegalArgumentException ignored) {
                // Ignore a damaged entry while leaving other aliases usable.
            }
        }
    }

    private static synchronized void saveKeys() throws IOException {
        File file = backingFile();
        File parent = file.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs() && !parent.isDirectory()) {
            throw new IOException("cannot create " + parent);
        }
        Properties properties = new Properties();
        for (Map.Entry<String, SecretKey> entry : KEYS.entrySet()) {
            byte[] encoded = entry.getValue().getEncoded();
            if (encoded != null) properties.setProperty(entry.getKey(), Base64.getEncoder().encodeToString(encoded));
        }
        File temporary = new File(file.getPath() + ".tmp");
        try (OutputStream out = new FileOutputStream(temporary)) {
            properties.store(out, "artzig AndroidKeyStore compatibility keys");
        }
        if (!temporary.renameTo(file)) {
            try (InputStream in = new FileInputStream(temporary); OutputStream out = new FileOutputStream(file)) {
                byte[] buffer = new byte[4096];
                for (int n; (n = in.read(buffer)) >= 0;) out.write(buffer, 0, n);
            }
            temporary.delete();
        }
        file.setReadable(false, false);
        file.setWritable(false, false);
        file.setReadable(true, true);
        file.setWritable(true, true);
    }

    private static synchronized SecretKey getKey(String alias) throws IOException {
        loadKeys();
        return KEYS.get(alias);
    }

    private static synchronized void putKey(String alias, SecretKey key) throws IOException {
        loadKeys();
        KEYS.put(alias, key);
        saveKeys();
    }

    public static final class Store extends KeyStoreSpi {
        public Store() {}

        @Override public Key engineGetKey(String alias, char[] password) throws UnrecoverableKeyException {
            try { return getKey(alias); } catch (IOException e) { throw new UnrecoverableKeyException(e.toString()); }
        }
        @Override public Certificate[] engineGetCertificateChain(String alias) { return null; }
        @Override public Certificate engineGetCertificate(String alias) { return null; }
        @Override public Date engineGetCreationDate(String alias) { return engineContainsAlias(alias) ? new Date(0) : null; }
        @Override public void engineSetKeyEntry(String alias, Key key, char[] password, Certificate[] chain)
                throws KeyStoreException {
            if (!(key instanceof SecretKey) || key.getEncoded() == null) throw new KeyStoreException("secret keys only");
            try { putKey(alias, (SecretKey) key); } catch (IOException e) { throw new KeyStoreException(e); }
        }
        @Override public void engineSetKeyEntry(String alias, byte[] key, Certificate[] chain)
                throws KeyStoreException {
            try { putKey(alias, new SecretKeySpec(key, "AES")); } catch (IOException e) { throw new KeyStoreException(e); }
        }
        @Override public void engineSetCertificateEntry(String alias, Certificate cert) throws KeyStoreException {
            throw new KeyStoreException("certificates are unsupported");
        }
        @Override public void engineDeleteEntry(String alias) throws KeyStoreException {
            try { loadKeys(); KEYS.remove(alias); saveKeys(); } catch (IOException e) { throw new KeyStoreException(e); }
        }
        @Override public Enumeration<String> engineAliases() {
            try { loadKeys(); } catch (IOException ignored) {}
            return Collections.enumeration(KEYS.keySet());
        }
        @Override public boolean engineContainsAlias(String alias) {
            try { return getKey(alias) != null; } catch (IOException e) { return false; }
        }
        @Override public int engineSize() {
            try { loadKeys(); } catch (IOException ignored) {}
            return KEYS.size();
        }
        @Override public boolean engineIsKeyEntry(String alias) { return engineContainsAlias(alias); }
        @Override public boolean engineIsCertificateEntry(String alias) { return false; }
        @Override public String engineGetCertificateAlias(Certificate cert) { return null; }
        @Override public void engineStore(OutputStream stream, char[] password)
                throws IOException, NoSuchAlgorithmException, CertificateException { saveKeys(); }
        @Override public void engineLoad(InputStream stream, char[] password)
                throws IOException, NoSuchAlgorithmException, CertificateException { loadKeys(); }
        @Override public void engineLoad(KeyStore.LoadStoreParameter parameter)
                throws IOException, NoSuchAlgorithmException, CertificateException { loadKeys(); }
    }

    public static final class Generator extends KeyGeneratorSpi {
        private SecureRandom random = new SecureRandom();
        private String alias;
        private int keySize = 256;

        public Generator() {}

        @Override protected void engineInit(SecureRandom random) {
            if (random != null) this.random = random;
        }
        @Override protected void engineInit(int keySize, SecureRandom random) {
            this.keySize = keySize;
            if (random != null) this.random = random;
        }
        @Override protected void engineInit(AlgorithmParameterSpec params, SecureRandom random)
                throws InvalidAlgorithmParameterException {
            if (params == null) throw new InvalidAlgorithmParameterException("missing KeyGenParameterSpec");
            if (random != null) this.random = random;
            try {
                Method getAlias = params.getClass().getMethod("getKeystoreAlias");
                alias = (String) getAlias.invoke(params);
                try {
                    int requested = ((Number) params.getClass().getMethod("getKeySize").invoke(params)).intValue();
                    if (requested > 0) keySize = requested;
                } catch (ReflectiveOperationException ignored) {}
            } catch (ReflectiveOperationException | ClassCastException e) {
                throw new InvalidAlgorithmParameterException("unsupported parameters: " + params.getClass(), e);
            }
        }
        @Override protected SecretKey engineGenerateKey() {
            if (alias == null || alias.isEmpty()) throw new ProviderException("key alias was not initialized");
            byte[] encoded = new byte[Math.max(128, keySize) / 8];
            random.nextBytes(encoded);
            SecretKey key = new SecretKeySpec(encoded, "AES");
            try { putKey(alias, key); } catch (IOException e) { throw new ProviderException(e); }
            return key;
        }
    }
}
