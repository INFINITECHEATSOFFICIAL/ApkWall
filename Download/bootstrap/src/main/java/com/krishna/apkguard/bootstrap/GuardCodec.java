package com.krishna.apkguard.bootstrap;

import android.content.res.AssetManager;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PushbackInputStream;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Arrays;
import java.util.Base64;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/** Runtime decoder referenced by DEX instructions rewritten by APK Wall. */
public final class GuardCodec {
    private static final String STRING_PREFIX = "__KGSTR1__";
    private static final byte[] ASSET_MAGIC = new byte[] { 0x00, 0x4b, 0x47, 0x41, 0x53, 0x45, 0x43, 0x31 };
    private static final int MAX_ENCRYPTED_ASSET_BYTES = 1024 * 1024 + 64;
    private static volatile byte[] key;

    private GuardCodec() { }

    static void initialize(byte[] aesKey) {
        if (aesKey == null || aesKey.length != 32) {
            throw new IllegalArgumentException("Invalid APK Wall AES key");
        }
        byte[] previous = key;
        key = Arrays.copyOf(aesKey, aesKey.length);
        if (previous != null) Arrays.fill(previous, (byte) 0);
    }

    public static String decodeString(String value) {
        if (value == null || !value.startsWith(STRING_PREFIX)) return value;
        try {
            byte[] blob = Base64.getDecoder().decode(value.substring(STRING_PREFIX.length()));
            byte[] plain = decryptBlob(blob);
            return new String(plain, StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("APK Wall could not authenticate an encrypted string literal", e);
        }
    }

    public static InputStream openAsset(AssetManager manager, String name) throws IOException {
        return unwrapAsset(manager.open(name));
    }

    public static InputStream openAsset(AssetManager manager, String name, int accessMode) throws IOException {
        return unwrapAsset(manager.open(name, accessMode));
    }

    private static InputStream unwrapAsset(InputStream raw) throws IOException {
        PushbackInputStream input = new PushbackInputStream(raw, ASSET_MAGIC.length);
        byte[] prefix = new byte[ASSET_MAGIC.length];
        int count = readSome(input, prefix);
        if (count != ASSET_MAGIC.length || !Arrays.equals(prefix, ASSET_MAGIC)) {
            if (count > 0) input.unread(prefix, 0, count);
            return input;
        }
        byte[] blob;
        try {
            blob = readAllLimited(input, MAX_ENCRYPTED_ASSET_BYTES);
        } finally {
            input.close();
        }
        if (blob.length < 28) throw new IOException("Encrypted asset payload is truncated");
        try {
            return new ByteArrayInputStream(decryptBlob(blob));
        } catch (GeneralSecurityException e) {
            throw new IOException("APK Wall could not authenticate an encrypted asset", e);
        }
    }

    private static byte[] decryptBlob(byte[] blob) throws GeneralSecurityException {
        if (blob == null || blob.length < 28) throw new GeneralSecurityException("Encrypted payload is too short");
        byte[] currentKey = key;
        if (currentKey == null || currentKey.length != 32) throw new GeneralSecurityException("Runtime key is not initialized");
        byte[] nonce = Arrays.copyOfRange(blob, 0, 12);
        byte[] ciphertext = Arrays.copyOfRange(blob, 12, blob.length);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(currentKey, "AES"), new GCMParameterSpec(128, nonce));
        return cipher.doFinal(ciphertext);
    }

    private static int readSome(InputStream input, byte[] buffer) throws IOException {
        int offset = 0;
        while (offset < buffer.length) {
            int count = input.read(buffer, offset, buffer.length - offset);
            if (count < 0) break;
            if (count == 0) continue;
            offset += count;
        }
        return offset;
    }

    private static byte[] readAllLimited(InputStream input, int maxBytes) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int count;
        while ((count = input.read(buffer)) != -1) {
            if (out.size() + count > maxBytes) throw new IOException("Encrypted asset exceeds APK Wall's 1 MiB limit");
            out.write(buffer, 0, count);
        }
        return out.toByteArray();
    }
}
