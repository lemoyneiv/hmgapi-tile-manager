package dev.lemoyne.tiles;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.Process;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Lightweight IPC ContentProvider bridging com.android.launcher3, com.hkmc.system.app.homesetting,
 * and the Tile Manager UI on stock Open-HMG/HMG-API without requiring any core daemon changes:
 *  - putCatalog / getCatalog: live catalog of stock + mod launcher tiles pushed by launcher3
 *  - getConfig / putConfig: instant cross-process tile visibility & gap-compaction settings
 */
public final class TileCatalogProvider extends ContentProvider {
    static final String AUTHORITY = "dev.lemoyne.tiles.provider";
    static final Uri URI = Uri.parse("content://" + AUTHORITY);
    static final String ACTION_TILES_CHANGED = "dev.lemoyne.tiles.ACTION_TILES_CHANGED";
    static final String ACTION_REQUEST_CATALOG = "dev.lemoyne.tiles.ACTION_REQUEST_CATALOG";

    private static final String CATALOG_FILE = "catalog.json";
    private static final String CONFIG_FILE = "config.json";
    private static final String LEGACY_PUBLIC_CONFIG = "/data/hmgapi/public/launcher-tiles.json";
    private static final String LEGACY_LAUNCHER_CATALOG =
            "/data/data/com.android.launcher3/files/hmgapi-launcher-catalog.json";
    private static final File PUBLIC_STATE = new File("/data/hmgapi/public/state.json");

    private static final Object LOCK = new Object();
    private static volatile Runnable catalogListener;

    static void setCatalogListener(Runnable listener) {
        catalogListener = listener;
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Bundle call(String method, String arg, Bundle extras) {
        Context ctx = getContext();
        if (ctx == null || method == null) return null;

        if ("putCatalog".equals(method)) {
            if (!isAllowedCatalogSender(ctx, Binder.getCallingUid())) return null;
            if (arg == null || arg.trim().isEmpty()) return null;
            try {
                JSONObject doc = new JSONObject(arg);
                JSONArray arr = doc.optJSONArray("tiles");
                if (arr == null) return null;
                synchronized (LOCK) {
                    writeAtomic(new File(ctx.getFilesDir(), CATALOG_FILE), doc.toString());
                }
                Runnable l = catalogListener;
                if (l != null) l.run();
                Bundle out = new Bundle();
                out.putBoolean("ok", true);
                return out;
            } catch (Throwable ignored) {
                return null;
            }
        }

        if ("getCatalog".equals(method)) {
            String json = readCatalogJson(ctx);
            Bundle out = new Bundle();
            if (json != null) out.putString("catalog_json", json);
            return out;
        }

        if ("getConfig".equals(method)) {
            String json = readEffectiveConfigJson(ctx);
            Bundle out = new Bundle();
            if (json != null) out.putString("config_json", json);
            return out;
        }

        if ("putConfig".equals(method)) {
            if (Binder.getCallingUid() != Process.myUid()) return null;
            if (arg == null || arg.trim().isEmpty()) return null;
            try {
                JSONObject cfg = new JSONObject(arg);
                synchronized (LOCK) {
                    writeAtomic(new File(ctx.getFilesDir(), CONFIG_FILE), cfg.toString());
                }
                Bundle out = new Bundle();
                out.putBoolean("ok", true);
                return out;
            } catch (Throwable ignored) {
                return null;
            }
        }

        return null;
    }

    static String readCatalogJson(Context ctx) {
        synchronized (LOCK) {
            File f = new File(ctx.getFilesDir(), CATALOG_FILE);
            if (f.isFile()) {
                try {
                    return readUtf8(f);
                } catch (Throwable ignored) {}
            }
            File legacy = new File(LEGACY_LAUNCHER_CATALOG);
            if (legacy.isFile() && legacy.canRead()) {
                try {
                    return readUtf8(legacy);
                } catch (Throwable ignored) {}
            }
            return null;
        }
    }

    static String readEffectiveConfigJson(Context ctx) {
        synchronized (LOCK) {
            File cfgFile = new File(ctx.getFilesDir(), CONFIG_FILE);
            if (cfgFile.isFile()) {
                try {
                    String localRaw = readUtf8(cfgFile);
                    JSONObject local = new JSONObject(localRaw);
                    // If previously synced with HMG-API and the user edited settings in HMG-API's SettingsForm,
                    // public/state.json will have a strictly newer mtime than config.json.
                    if (local.optBoolean("synced_hmgapi", false)
                            && PUBLIC_STATE.isFile()
                            && PUBLIC_STATE.lastModified() > cfgFile.lastModified() + 1000L) {
                        JSONObject fromState = readStateSettingsJson();
                        if (fromState != null) {
                            writeAtomic(cfgFile, fromState.toString());
                            return fromState.toString();
                        }
                    }
                    return localRaw;
                } catch (Throwable ignored) {}
            }

            // First-run bootstrap: check HMG-API public/state.json or legacy launcher-tiles.json
            JSONObject fromState = readStateSettingsJson();
            if (fromState != null && (fromState.optJSONArray("hidden") != null
                    && fromState.optJSONArray("hidden").length() > 0
                    || !fromState.optBoolean("compact_gaps", true))) {
                try {
                    writeAtomic(cfgFile, fromState.toString());
                } catch (Throwable ignored) {}
                return fromState.toString();
            }

            File legacy = new File(LEGACY_PUBLIC_CONFIG);
            if (legacy.isFile() && legacy.canRead()) {
                try {
                    JSONObject leg = new JSONObject(readUtf8(legacy));
                    JSONObject migrated = new JSONObject();
                    migrated.put("configured", true);
                    migrated.put("compact_gaps", leg.optBoolean("compact_gaps", true));
                    JSONArray hidden = leg.optJSONArray("hidden");
                    migrated.put("hidden", hidden != null ? hidden : new JSONArray());
                    migrated.put("synced_hmgapi", false);
                    writeAtomic(cfgFile, migrated.toString());
                    return migrated.toString();
                } catch (Throwable ignored) {}
            }
            return null;
        }
    }

    static void writeLocalConfig(Context ctx, boolean compactGaps, Set<String> hiddenKeys, boolean syncedHmgApi) {
        synchronized (LOCK) {
            try {
                JSONObject out = new JSONObject();
                out.put("configured", true);
                out.put("compact_gaps", compactGaps);
                JSONArray arr = new JSONArray();
                for (String k : hiddenKeys) arr.put(k);
                out.put("hidden", arr);
                out.put("synced_hmgapi", syncedHmgApi);
                writeAtomic(new File(ctx.getFilesDir(), CONFIG_FILE), out.toString());
            } catch (Throwable ignored) {}
        }
    }

    private static JSONObject readStateSettingsJson() {
        if (!PUBLIC_STATE.isFile()) return null;
        try {
            JSONObject root = new JSONObject(readUtf8(PUBLIC_STATE));
            JSONObject mods = root.optJSONObject("mods");
            if (mods == null) return null;
            JSONObject self = mods.optJSONObject("hmgapi-tile-manager");
            if (self == null) return null;
            JSONObject settings = self.optJSONObject("settings");
            if (settings == null) return null;

            boolean compact = settings.optBoolean("compact_gaps", true);
            String rawHidden = settings.optString("hidden_keys", "");
            Set<String> norm = new LinkedHashSet<String>();
            if (rawHidden != null && !rawHidden.isEmpty()) {
                for (String part : rawHidden.split(",")) {
                    String k = normKeyString(part);
                    if (!k.isEmpty()) norm.add(k);
                }
            }
            JSONObject out = new JSONObject();
            out.put("configured", true);
            out.put("compact_gaps", compact);
            JSONArray arr = new JSONArray();
            for (String k : norm) arr.put(k);
            out.put("hidden", arr);
            out.put("synced_hmgapi", true);
            return out;
        } catch (Throwable ignored) {
            return null;
        }
    }

    static String normKey(String pkg, String cls) {
        String p = pkg == null ? "" : pkg.trim();
        String c = cls == null ? "" : cls.trim();
        if (!p.isEmpty() && c.startsWith(".")) c = p + c;
        if (p.isEmpty() || c.isEmpty()) return "";
        return p + "/" + c;
    }

    static String normKeyString(String raw) {
        if (raw == null) return "";
        String s = raw.trim();
        int slash = s.indexOf('/');
        if (slash <= 0 || slash + 1 >= s.length()) return "";
        return normKey(s.substring(0, slash), s.substring(slash + 1));
    }

    private static boolean isAllowedCatalogSender(Context ctx, int uid) {
        if (uid == Process.myUid() || uid == Process.SYSTEM_UID || uid == 0) return true;
        try {
            PackageManager pm = ctx.getPackageManager();
            ApplicationInfo ai = pm.getApplicationInfo("com.android.launcher3", 0);
            if (ai != null && ai.uid == uid) return true;
            String[] pkgs = pm.getPackagesForUid(uid);
            if (pkgs != null) {
                for (String p : pkgs) {
                    if ("com.android.launcher3".equals(p)) return true;
                }
            }
        } catch (Throwable ignored) {}
        return false;
    }

    static String readUtf8(File f) throws IOException {
        FileInputStream in = new FileInputStream(f);
        try {
            byte[] buf = new byte[(int) f.length()];
            int off = 0;
            while (off < buf.length) {
                int n = in.read(buf, off, buf.length - off);
                if (n < 0) break;
                off += n;
            }
            return new String(buf, 0, off, "UTF-8");
        } finally {
            in.close();
        }
    }

    private static void writeAtomic(File dst, String content) throws IOException {
        File dir = dst.getParentFile();
        if (dir != null && !dir.exists()) dir.mkdirs();
        File tmp = new File(dst.getPath() + ".tmp");
        FileOutputStream out = new FileOutputStream(tmp);
        try {
            out.write(content.getBytes("UTF-8"));
            out.getFD().sync();
        } finally {
            out.close();
        }
        if (!tmp.renameTo(dst)) {
            throw new IOException("can't rename " + tmp + " to " + dst);
        }
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        return null;
    }

    @Override
    public String getType(Uri uri) {
        return null;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }
}
