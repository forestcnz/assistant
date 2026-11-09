package com.forest.android.speed;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.Process;
import android.util.AtomicFile;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;

/** Loaded inside the selected app. No dependency on the assistant's classes. */
public final class SpeedInitProvider extends ContentProvider {
    private JSONObject config;
    private File stateFile, statusFile;
    private SharedPreferences preferences;
    private volatile String loadError = "";
    private volatile boolean loaded;

    @Override public boolean onCreate() {
        try {
            Context context = getContext();
            config = new JSONObject(new String(readAsset("config.json"), StandardCharsets.UTF_8));
            preferences = context.getSharedPreferences("forest_speed_runtime", Context.MODE_PRIVATE);
            File stateDirectory = new File(context.getFilesDir(), "forest-speed");
            if (!stateDirectory.isDirectory() && !stateDirectory.mkdirs()) throw new IllegalStateException("无法创建变速配置目录");
            stateFile = new File(stateDirectory, "state.json");
            statusFile = new File(stateDirectory, "status.json");
            Files.deleteIfExists(statusFile.toPath());
            writeState(preferences.getFloat("factor", (float) config.optDouble("factor", 1)),
                    preferences.getBoolean("native", config.optBoolean("nativeEnabled", true)));
            File directory = new File(context.getCodeCacheDir(), "forest-speed-v1");
            if (!directory.isDirectory() && !directory.mkdirs()) throw new IllegalStateException("无法创建变速模块目录");
            String abi = null;
            for (String candidate : Build.SUPPORTED_ABIS) {
                boolean is64 = candidate.equals("arm64-v8a") || candidate.equals("x86_64");
                if (is64 != Process.is64Bit()) continue;
                try (InputStream ignored = context.getAssets().open("forest-speed/gadget/" + candidate + ".so")) {
                    abi = candidate; break;
                } catch (java.io.IOException ignored) { }
            }
            if (abi == null) throw new IllegalStateException("此应用的处理器架构暂不支持变速");
            File gadget = copyAsset(directory, "libforest_speed_gadget.so", "gadget/" + abi + ".so");
            File script = copyAsset(directory, "hook.js", "hook.js");
            JSONObject interaction = new JSONObject().put("type", "script").put("path", script.getAbsolutePath())
                    .put("parameters", new JSONObject().put("statePath", stateFile.getAbsolutePath())
                            .put("statusPath", statusFile.getAbsolutePath()));
            File configuration = new File(directory, "libforest_speed_gadget.config.so");
            Files.deleteIfExists(configuration.toPath());
            Files.write(configuration.toPath(), new JSONObject().put("interaction", interaction)
                    .toString().getBytes(StandardCharsets.UTF_8));
            System.load(gadget.getAbsolutePath());
            loaded = true;
        } catch (Throwable error) {
            loadError = error.getClass().getSimpleName() + ": " + error.getMessage();
            android.util.Log.e("ForestSpeed", "变速模块启动失败", error);
        }
        return true; // A failed hook must never prevent the original app from starting.
    }

    private byte[] readAsset(String name) throws Exception {
        try (InputStream input = getContext().getAssets().open("forest-speed/" + name);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            copy(input, output); return output.toByteArray();
        }
    }

    private File copyAsset(File directory, String name, String asset) throws Exception {
        // Re-copy on startup so an APK update replaces the old payload as well.
        File destination = new File(directory, name);
        Files.deleteIfExists(destination.toPath());
        File temporary = File.createTempFile("payload", ".tmp", directory);
        try {
            try (InputStream input = getContext().getAssets().open("forest-speed/" + asset);
                 FileOutputStream output = new FileOutputStream(temporary)) {
                copy(input, output);
                output.getFD().sync();
            }
            if (!temporary.setReadOnly()) throw new IllegalStateException("无法保护变速模块文件");
            Files.move(temporary.toPath(), destination.toPath());
        } finally { Files.deleteIfExists(temporary.toPath()); }
        return destination;
    }

    private synchronized void writeState(float factor, boolean nativeEnabled) throws Exception {
        if (!Float.isFinite(factor) || factor < .25f || factor > 10f) throw new IllegalArgumentException("倍率须在 0.25 到 10 之间");
        AtomicFile atomic = new AtomicFile(stateFile);
        FileOutputStream output = atomic.startWrite();
        try {
            output.write(new JSONObject().put("factor", factor).put("nativeEnabled", nativeEnabled)
                    .toString().getBytes(StandardCharsets.UTF_8));
            atomic.finishWrite(output);
        } catch (Throwable error) { atomic.failWrite(output); throw error; }
        if (!preferences.edit().putFloat("factor", factor).putBoolean("native", nativeEnabled).commit())
            throw new IllegalStateException("无法保存变速设置");
    }

    private void enforceCaller() {
        if (Binder.getCallingUid() == Process.myUid()) return;
        if (config == null) throw new SecurityException("变速模块未初始化");
        try {
            String host = config.getString("hostPackage");
            String[] packages = getContext().getPackageManager().getPackagesForUid(Binder.getCallingUid());
            boolean matches = false;
            if (packages != null) for (String name : packages) if (host.equals(name)) matches = true;
            if (!matches) throw new SecurityException("调用方不是配置的助手");
            Signature[] signatures = getContext().getPackageManager().getPackageInfo(host,
                    PackageManager.GET_SIGNING_CERTIFICATES).signingInfo.getApkContentsSigners();
            for (Signature signature : signatures) {
                byte[] digest = MessageDigest.getInstance("SHA-256").digest(signature.toByteArray());
                StringBuilder hex = new StringBuilder();
                for (byte value : digest) hex.append(String.format(java.util.Locale.ROOT, "%02x", value & 255));
                if (hex.toString().equals(config.getString("hostCertificate"))) return;
            }
            throw new SecurityException("助手签名不匹配，请重新生成补丁");
        } catch (SecurityException error) { throw error; }
        catch (Exception error) { throw new SecurityException("无法校验助手身份", error); }
    }

    @Override public Bundle call(String method, String arg, Bundle extras) {
        enforceCaller();
        Bundle result = new Bundle();
        try {
            if (stateFile == null || preferences == null) throw new IllegalStateException(loadError);
            if ("set_speed".equals(method)) {
                if (extras == null || !extras.containsKey("factor")) throw new IllegalArgumentException("缺少倍率");
                writeState(extras.getFloat("factor"), extras.getBoolean("nativeEnabled", true));
            } else if (!"status".equals(method)) throw new IllegalArgumentException("不支持的操作");
            result.putFloat("factor", preferences.getFloat("factor", 1));
            result.putBoolean("nativeEnabled", preferences.getBoolean("native", true));
            result.putBoolean("loaded", loaded);
            result.putString("error", loadError);
            if (statusFile.isFile()) {
                try {
                    JSONObject status = new JSONObject(new String(Files.readAllBytes(statusFile.toPath()), StandardCharsets.UTF_8));
                    result.putBoolean("javaReady", status.optBoolean("javaReady"));
                    result.putInt("nativeHooks", status.optInt("nativeHooks"));
                    result.putLong("nativeCalls", status.optLong("nativeCalls"));
                    if (loadError.isEmpty()) result.putString("error", status.optString("error"));
                } catch (Exception ignored) { /* Next status request retries an in-progress write. */ }
            }
        } catch (Exception error) { result.putString("error", String.valueOf(error.getMessage())); }
        return result;
    }

    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String order) { enforceCaller(); return null; }
    @Override public String getType(Uri uri) { enforceCaller(); return null; }
    @Override public Uri insert(Uri uri, ContentValues values) { enforceCaller(); throw new UnsupportedOperationException(); }
    @Override public int delete(Uri uri, String selection, String[] args) { enforceCaller(); throw new UnsupportedOperationException(); }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] args) { enforceCaller(); throw new UnsupportedOperationException(); }
    private static void copy(InputStream input, java.io.OutputStream output) throws java.io.IOException {
        byte[] buffer = new byte[65536]; int count;
        while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
    }
}
