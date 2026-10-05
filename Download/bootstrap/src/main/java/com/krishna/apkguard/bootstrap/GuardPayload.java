package com.krishna.apkguard.bootstrap;

import android.content.pm.ApplicationInfo;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import javax.crypto.Cipher;
import javax.crypto.CipherInputStream;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

final class GuardPayload {
    private static final int MAGIC_V1 = 0x4B475031; // KGP1
    private static final int MAGIC_V2 = 0x4B475032; // KGP2
    private static final String CONFIG_ENTRY = "assets/.krishna-guard/config.bin";
    // This only obscures per-output key bytes in the small configuration entry. It is not unbreakable key storage.
    private static final byte[] MASK = new byte[] {
            0x31, 0x6A, 0x5D, 0x22, 0x47, 0x19, 0x73, 0x4B,
            0x2C, 0x55, 0x08, 0x6F, 0x14, 0x3D, 0x62, 0x27
    };

    private final String originalApplicationClass;
    private final String originalFactoryClass;
    private final String dexPath;
    private final int protectionFlags;

    private GuardPayload(String originalApplicationClass, String originalFactoryClass, String dexPath, int protectionFlags) {
        this.originalApplicationClass = originalApplicationClass;
        this.originalFactoryClass = originalFactoryClass;
        this.dexPath = dexPath;
        this.protectionFlags = protectionFlags;
    }

    String getOriginalApplicationClass() { return originalApplicationClass; }
    String getOriginalFactoryClass() { return originalFactoryClass; }
    String getDexPath() { return dexPath; }
    int getProtectionFlags() { return protectionFlags; }

    static GuardPayload openAndDecrypt(ApplicationInfo appInfo) throws Exception {
        byte[] config;
        List<String> encryptedEntries = new ArrayList<>();
        byte[] key = new byte[32];
        int protectionFlags = 0;
        String originalApplication;
        String originalFactory;
        try {
            try (ZipFile apk = new ZipFile(appInfo.sourceDir)) {
                ZipEntry entry = apk.getEntry(CONFIG_ENTRY);
                if (entry == null) throw new IllegalStateException("Protected DEX configuration is missing");
                try (InputStream input = apk.getInputStream(entry)) {
                    config = readAll(input, 64 * 1024);
                }
            }

            try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(config))) {
                int magic = input.readInt();
                if (magic != MAGIC_V1 && magic != MAGIC_V2) {
                    throw new IllegalStateException("Invalid protected DEX configuration");
                }
                originalApplication = input.readUTF();
                originalFactory = input.readUTF();
                int count = input.readInt();
                if (count < 1 || count > 64) throw new IllegalStateException("Invalid protected DEX count");
                for (int i = 0; i < count; i++) encryptedEntries.add(input.readUTF());
                if (magic == MAGIC_V2) protectionFlags = input.readInt();
                byte[] masked = new byte[key.length];
                input.readFully(masked);
                for (int i = 0; i < key.length; i++) key[i] = (byte) (masked[i] ^ MASK[i % MASK.length]);
                if (input.available() != 0) throw new IllegalStateException("Unexpected bytes in DEX configuration");
            }

            int codecFlags = GuardRuntimeChecks.FLAG_ENCRYPT_STRINGS | GuardRuntimeChecks.FLAG_ENCRYPT_ASSETS;
            if ((protectionFlags & codecFlags) != 0) GuardCodec.initialize(key);

            File privateDir = new File(appInfo.dataDir, "code_cache/krishna-apk-guard");
            if (!privateDir.isDirectory() && !privateDir.mkdirs()) {
                throw new IllegalStateException("Cannot create private DEX directory");
            }
            List<String> dexPaths = new ArrayList<>();
            try (ZipFile apk = new ZipFile(appInfo.sourceDir)) {
                for (int i = 0; i < encryptedEntries.size(); i++) {
                    String entryName = encryptedEntries.get(i);
                    ZipEntry entry = apk.getEntry(entryName);
                    if (entry == null || entry.getSize() < 28 || entry.getSize() > 512L * 1024L * 1024L) {
                        throw new IllegalStateException("Invalid encrypted DEX payload entry");
                    }
                    File dexFile = new File(privateDir, "payload-" + i + ".dex");
                    if (dexFile.exists() && !dexFile.delete()) throw new IllegalStateException("Cannot replace a cached DEX file");

                    // Android 14+ requires dynamically loaded code to be read-only. Open first,
                    // set the mode before writing, then write through that already-open descriptor.
                    try (InputStream raw = apk.getInputStream(entry);
                         FileOutputStream output = new FileOutputStream(dexFile)) {
                        if (!dexFile.setReadOnly()) throw new IllegalStateException("Cannot mark dynamically loaded DEX read-only");
                        byte[] nonce = new byte[12];
                        int got = readFully(raw, nonce);
                        if (got != nonce.length) throw new IllegalStateException("Encrypted DEX nonce is truncated");
                        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
                        cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
                        try (CipherInputStream decrypted = new CipherInputStream(raw, cipher)) {
                            byte[] buffer = new byte[64 * 1024];
                            int size;
                            while ((size = decrypted.read(buffer)) != -1) output.write(buffer, 0, size);
                            output.getFD().sync();
                        }
                    }
                    if (!dexFile.isFile() || dexFile.length() < 112L || !dexFile.setReadOnly()) {
                        throw new IllegalStateException("Decrypted DEX is invalid or not read-only");
                    }
                    dexPaths.add(dexFile.getAbsolutePath());
                }
            }
            StringBuilder path = new StringBuilder();
            for (String dex : dexPaths) {
                if (path.length() > 0) path.append(File.pathSeparatorChar);
                path.append(dex);
            }
            return new GuardPayload(originalApplication, originalFactory, path.toString(), protectionFlags);
        } finally {
            Arrays.fill(key, (byte) 0);
        }
    }

    private static byte[] readAll(InputStream input, int max) throws Exception {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int count;
        while ((count = input.read(buffer)) != -1) {
            if (out.size() + count > max) throw new IllegalStateException("Protected DEX configuration is too large");
            out.write(buffer, 0, count);
        }
        return out.toByteArray();
    }

    private static int readFully(InputStream input, byte[] buffer) throws Exception {
        int offset = 0;
        while (offset < buffer.length) {
            int count = input.read(buffer, offset, buffer.length - offset);
            if (count < 0) break;
            offset += count;
        }
        return offset;
    }
}
