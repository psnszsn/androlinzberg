package android.security.keystore;

import java.security.spec.AlgorithmParameterSpec;

/** Hosted subset of Android's KeyGenParameterSpec used by application key generators. */
public final class KeyGenParameterSpec implements AlgorithmParameterSpec {
    private final String alias;
    private final int purposes;
    private final int keySize;
    private final String[] blockModes;
    private final String[] encryptionPaddings;

    private KeyGenParameterSpec(Builder builder) {
        alias = builder.alias;
        purposes = builder.purposes;
        keySize = builder.keySize;
        blockModes = builder.blockModes.clone();
        encryptionPaddings = builder.encryptionPaddings.clone();
    }

    public String getKeystoreAlias() { return alias; }
    public int getPurposes() { return purposes; }
    public int getKeySize() { return keySize; }
    public String[] getBlockModes() { return blockModes.clone(); }
    public String[] getEncryptionPaddings() { return encryptionPaddings.clone(); }

    public static final class Builder {
        private final String alias;
        private final int purposes;
        private int keySize = 256;
        private String[] blockModes = new String[0];
        private String[] encryptionPaddings = new String[0];

        public Builder(String alias, int purposes) {
            if (alias == null || alias.isEmpty()) throw new IllegalArgumentException("alias is empty");
            this.alias = alias;
            this.purposes = purposes;
        }
        public Builder setKeySize(int keySize) { this.keySize = keySize; return this; }
        public Builder setBlockModes(String... modes) { blockModes = modes.clone(); return this; }
        public Builder setEncryptionPaddings(String... paddings) { encryptionPaddings = paddings.clone(); return this; }
        public KeyGenParameterSpec build() { return new KeyGenParameterSpec(this); }
    }
}
