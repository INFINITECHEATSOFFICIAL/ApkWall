package com.krishna.apkguard.bootstrap;

import android.os.Build;
import android.os.Debug;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Opt-in heuristic startup checks. They are bypassable and can produce false positives. */
final class GuardRuntimeChecks {
    static final int FLAG_ENCRYPT_STRINGS = 1;
    static final int FLAG_ENCRYPT_ASSETS = 1 << 1;
    static final int FLAG_BLOCK_ROOT = 1 << 2;
    static final int FLAG_BLOCK_EMULATOR = 1 << 3;
    static final int FLAG_BLOCK_DEBUGGER = 1 << 4;
    static final int FLAG_BLOCK_INSTRUMENTATION = 1 << 5;

    private static final String TAG = "KrishnaApkWall";

    private GuardRuntimeChecks() { }

    static void enforce(int flags) {
        List<String> detected = new ArrayList<>();
        if ((flags & FLAG_BLOCK_DEBUGGER) != 0 && (Debug.isDebuggerConnected() || Debug.waitingForDebugger())) {
            detected.add("debugger");
        }
        if ((flags & FLAG_BLOCK_ROOT) != 0 && isRootLikely()) {
            detected.add("root indicators");
        }
        if ((flags & FLAG_BLOCK_EMULATOR) != 0 && isEmulatorLikely()) {
            detected.add("emulator indicators");
        }
        if ((flags & FLAG_BLOCK_INSTRUMENTATION) != 0 && isInstrumentationLikely()) {
            detected.add("known instrumentation markers");
        }
        if (!detected.isEmpty()) {
            String reason = join(detected);
            Log.e(TAG, "Optional startup protection blocked this launch: " + reason);
            throw new IllegalStateException("APK Wall runtime check blocked startup: " + reason);
        }
    }

    private static boolean isRootLikely() {
        if (Build.TAGS != null && Build.TAGS.contains("test-keys")) return true;
        String[] candidates = {
                "/system/bin/su", "/system/xbin/su", "/sbin/su", "/su/bin/su",
                "/system/app/Superuser.apk", "/data/adb/magisk", "/data/adb/ksu"
        };
        for (String path : candidates) if (new File(path).exists()) return true;
        return false;
    }

    private static boolean isEmulatorLikely() {
        String fingerprint = lower(Build.FINGERPRINT);
        String model = lower(Build.MODEL);
        String product = lower(Build.PRODUCT);
        String hardware = lower(Build.HARDWARE);
        String manufacturer = lower(Build.MANUFACTURER);
        return fingerprint.startsWith("generic") || fingerprint.contains("emulator")
                || model.contains("emulator") || model.contains("sdk_gphone")
                || product.contains("sdk") || product.contains("emulator")
                || hardware.contains("goldfish") || hardware.contains("ranchu")
                || manufacturer.contains("genymotion");
    }

    private static boolean isInstrumentationLikely() {
        String[] paths = {
                "/data/local/tmp/frida-server", "/data/local/tmp/re.frida.server",
                "/system/framework/XposedBridge.jar", "/system/lib/libsubstrate.so",
                "/system/lib64/libsubstrate.so", "/data/adb/lspd", "/data/adb/modules/riru-core"
        };
        for (String path : paths) if (new File(path).exists()) return true;
        String[] markers = {"frida", "frida-gadget", "xposed", "substrate", "riru", "zygisk", "lspd"};
        try (BufferedReader reader = new BufferedReader(new FileReader("/proc/self/maps"))) {
            String line;
            int rows = 0;
            while ((line = reader.readLine()) != null && rows++ < 50000) {
                String value = line.toLowerCase(Locale.ROOT);
                for (String marker : markers) if (value.contains(marker)) return true;
            }
        } catch (Exception ignored) {
            // Restricted /proc access is not evidence that instrumentation is absent.
        }
        return false;
    }

    private static String lower(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }

    private static String join(List<String> values) {
        StringBuilder out = new StringBuilder();
        for (String value : values) {
            if (out.length() > 0) out.append(", ");
            out.append(value);
        }
        return out.toString();
    }
}
