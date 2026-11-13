package com.vseeheat.collector;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.net.LocalServerSocket;
import android.net.LocalSocket;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.system.Os;
import android.system.OsConstants;

import java.io.DataInputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/** Verifies existing root ADB or starts the bundled relay before reading the V6 profile. */
final class EmbeddedRootAdb {
    interface Progress { void update(String message); }

    private static final ComponentName VENDOR = new ComponentName(
            "com.define.shellservice", "com.define.shellservice.ShellService");

    private EmbeddedRootAdb() {}

    static void ensure(Context context, Progress progress)
            throws HeatLiveApkBuilder.BuildFailure {
        String stage = "VENDOR_CHECK";
        try {
            if (!VendorServiceGate.isTrusted(context)) {
                throw new IllegalStateException("V6 service check failed");
            }
            String source = context.getApplicationInfo().sourceDir;
            if (source == null || !source.startsWith("/data/app/")
                    || !source.endsWith("/base.apk") || source.contains("\n")
                    || source.contains("\r")) {
                throw new IllegalStateException("Builder install path changed");
            }
            progress.update("Starting secure V6 access…");
            stage = "EMBEDDED_STATUS";
            String status = action(context, source, "lan_status");
            // The normal app UID cannot read the system-owned relay journal.
            // RootTrial checks it while running under the verified system UID.
            if (healthy(status)) return;
            // An owner may have opened root ADB separately for this session.
            // Verify that listener and use it without stopping or restarting adbd.
            if ("tcp:5555".equals(prop("service.adb.listen_addrs"))) {
                stage = "EXISTING_VERIFY";
                TrustedRootAdb existing = TrustedRootAdb.findActive();
                if (!existing.isExternalTcp()) throw new IllegalStateException();
                existing.verifyRootShell();
                progress.update("Using the existing V6 access");
                return;
            }
            if (line(status, "LAN_STATE=needs_cleanup")) {
                stage = "CLEANUP";
                action(context, source, "lan_off");
                status = action(context, source, "lan_status");
            }
            if (!line(status, "LAN_STATE=off")) {
                throw new IllegalStateException("Another ADB session needs review");
            }
            stage = "USB_CHECK";
            if (!usbClear(context)) {
                throw new IllegalStateException("USB or wireless ADB state needs review");
            }
            stage = "EMBEDDED_START";
            String started = action(context, source, "lan_on");
            if (!healthy(started)) {
                throw new IllegalStateException("Root ADB did not verify");
            }
        } catch (Exception failure) {
            android.util.Log.w("VseeBuilderAccess", "Startup failed at " + stage
                    + " (" + failure.getClass().getSimpleName() + "): "
                    + safeReason(failure.getMessage()));
            throw new HeatLiveApkBuilder.BuildFailure("ADB_" + stage + "_FAILED");
        }
    }

    private static boolean usbClear(Context context) throws Exception {
        String config = prop("sys.usb.config");
        String state = prop("sys.usb.state");
        String tls = prop("persist.adb.tls_server.enable");
        if (!"1".equals(prop("ro.debuggable"))
                || !"stopped".equals(prop("init.svc.adbd"))
                || new File("/dev/usb-ffs/adb/ep0").exists()
                || !("".equals(tls) || "0".equals(tls))) return false;
        if (("none".equals(config) || "".equals(config))
                && ("none".equals(state) || "".equals(state))) return true;
        String adbSetting = Settings.Global.getString(context.getContentResolver(),
                "adb_enabled");
        if (!"adb".equals(config) || !"".equals(state)
                || !"0".equals(adbSetting)) return false;
        // The exact stale post-reset USB state was measured on the tested V6.
        String result = execute(context, context.getApplicationInfo().sourceDir,
                "debug=$(/system/bin/getprop ro.debuggable) || exit 1\n"
                + "adbd=$(/system/bin/getprop init.svc.adbd) || exit 1\n"
                + "config=$(/system/bin/getprop sys.usb.config) || exit 1\n"
                + "state=$(/system/bin/getprop sys.usb.state) || exit 1\n"
                + "tls=$(/system/bin/getprop persist.adb.tls_server.enable) || exit 1\n"
                + "adb_setting=$(/system/bin/settings get global adb_enabled) || exit 1\n"
                + "[ \"$debug\" = 1 ] && [ \"$adbd\" = stopped ] && [ \"$config\" = adb ] "
                + "&& [ -z \"$state\" ] && [ ! -e /dev/usb-ffs/adb/ep0 ] "
                + "&& [ \"$adb_setting\" = 0 ] || exit 1\n"
                + "case \"$tls\" in ''|0) ;; *) exit 1;; esac\n"
                + "/system/bin/setprop sys.usb.config none || exit 1\n"
                + "[ \"$(/system/bin/getprop sys.usb.config)\" = none ] || exit 1\n"
                + "printf 'CLEAR_STALE_USB=ok\\n'\n");
        return line(result, "CLEAR_STALE_USB=ok")
                && "none".equals(prop("sys.usb.config"));
    }

    private static String prop(String key) throws Exception {
        java.lang.Process child = new ProcessBuilder("/system/bin/getprop", key).start();
        try {
            byte[] output = child.getInputStream().readNBytes(256);
            if (!child.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)
                    || child.exitValue() != 0) throw new IllegalStateException();
            return new String(output, StandardCharsets.UTF_8).trim();
        } finally {
            child.destroy();
        }
    }

    private static String action(Context context, String source, String name)
            throws Exception {
        if (!"lan_on".equals(name) && !"lan_off".equals(name)
                && !"lan_status".equals(name)) throw new IllegalArgumentException();
        String path = quote(source);
        return execute(context, source, "CLASSPATH=" + path
                + " /system/bin/timeout 60 /system/bin/app_process /system/bin "
                + "com.torre.v6rootcheck.RootTrial " + name + " " + path + "\n");
    }

    private static String execute(Context context, String source, String body)
            throws Exception {
        String socketName = "v6builder_" + UUID.randomUUID().toString().replace("-", "");
        String challenge = UUID.randomUUID().toString();
        LocalServerSocket server = new LocalServerSocket(socketName);
        Handler main = new Handler(Looper.getMainLooper());
        Runnable expire = () -> close(server);
        main.postDelayed(expire, 72000);
        long deadline = SystemClock.elapsedRealtime() + 72000;
        try {
            String path = quote(source);
            String command = "{\nprintf 'SYSTEM_UID='; id -u\n" + body
                    + "\n} 2>&1 | CLASSPATH=" + path
                    + " /system/bin/app_process /system/bin "
                    + "com.torre.v6rootcheck.ResultBridge " + quote(socketName)
                    + " " + quote(challenge);
            if (context.startService(new Intent().setComponent(VENDOR)
                    .putExtra("command", command)) == null) {
                throw new IllegalStateException("V6 service did not start");
            }
            while (SystemClock.elapsedRealtime() < deadline) {
                try (LocalSocket peer = server.accept()) {
                    peer.setSoTimeout(4000);
                    if (peer.getPeerCredentials().getUid() != 1000) continue;
                    DataInputStream input = new DataInputStream(peer.getInputStream());
                    if (!challenge.equals(input.readUTF())) continue;
                    int length = input.readInt();
                    if (length < 1 || length > 24000) continue;
                    byte[] bytes = new byte[length];
                    input.readFully(bytes);
                    String response = new String(bytes, StandardCharsets.UTF_8);
                    if (!response.startsWith("SYSTEM_UID=1000\n")) {
                        throw new IllegalStateException("V6 service identity changed");
                    }
                    return response;
                } catch (java.io.IOException failure) {
                    if (SystemClock.elapsedRealtime() >= deadline) break;
                }
            }
            throw new IllegalStateException("No verified V6 service response");
        } finally {
            main.removeCallbacks(expire);
            close(server);
        }
    }

    private static boolean healthy(String report) {
        return line(report, "LAN_STATE=on") && line(report, "ADB_PEER_UID=0")
                && line(report, "ADB_SHELL_UID=0")
                && line(report, "LAN_ALLOWED=none");
    }

    private static boolean line(String report, String value) {
        return ("\n" + report + "\n").contains("\n" + value + "\n");
    }

    private static String safeReason(String reason) {
        if ("V6 service did not start".equals(reason)
                || "V6 service identity changed".equals(reason)
                || "No verified V6 service response".equals(reason)) return reason;
        return "other";
    }

    private static String quote(String value) {
        return "'" + value.replace("'", "'\"'\"'") + "'";
    }

    private static void close(LocalServerSocket server) {
        try { Os.shutdown(server.getFileDescriptor(), OsConstants.SHUT_RDWR); }
        catch (Exception ignored) { }
        try { server.close(); } catch (Exception ignored) { }
    }
}
