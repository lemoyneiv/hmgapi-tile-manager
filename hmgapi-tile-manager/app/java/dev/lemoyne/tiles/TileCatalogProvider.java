package dev.lemoyne.tiles;

import android.content.ComponentName;
import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.pm.ActivityInfo;
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
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Lightweight IPC ContentProvider bridging com.android.launcher3, com.hkmc.system.app.homesetting,
 * and the Tile Manager UI on stock Open-HMG/HMG-API without requiring any core daemon changes:
 *  - putCatalog / getCatalog: live schema-2 catalog of equipped stock + custom/mod launcher tiles
 *  - getConfig / putConfig: instant cross-process tile visibility & gap-compaction settings
 */
public final class TileCatalogProvider extends ContentProvider {
    static final int CATALOG_SCHEMA = 2;
    static final String AUTHORITY = "dev.lemoyne.tiles.provider";
    static final Uri URI = Uri.parse("content://" + AUTHORITY);
    static final Uri ALL_MENU = Uri.parse("content://com.hkmc.system.app.homesetting.provider/all_menu");
    static final String ACTION_TILES_CHANGED = "dev.lemoyne.tiles.ACTION_TILES_CHANGED";
    static final String ACTION_REQUEST_CATALOG = "dev.lemoyne.tiles.ACTION_REQUEST_CATALOG";

    private static final String CATALOG_FILE = "catalog.json";
    private static final String CONFIG_FILE = "config.json";
    private static final String LEGACY_PUBLIC_CONFIG = "/data/hmgapi/public/launcher-tiles.json";
    private static final File PUBLIC_STATE = new File("/data/hmgapi/public/state.json");
    private static final File MODS_ROOT = new File("/data/hmgapi/mods");

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
                int schema = doc.optInt("schema", 1);
                String toWrite;
                if (schema < CATALOG_SCHEMA) {
                    // Pre-reboot v1.0.0 hook in launcher3: enrich with live HMG-API mods + all_menu
                    // and mark hook_outdated=true so UI prompts for a reboot.
                    JSONObject upgraded = enrichBootstrapCatalog(ctx, doc, true);
                    toWrite = upgraded != null ? upgraded.toString() : doc.toString();
                } else {
                    toWrite = doc.toString();
                }
                synchronized (LOCK) {
                    writeAtomic(new File(ctx.getFilesDir(), CATALOG_FILE), toWrite);
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
                    String raw = readUtf8(f);
                    JSONObject doc = new JSONObject(raw);
                    if (doc.optInt("schema", 1) >= CATALOG_SCHEMA) {
                        return raw;
                    }
                    // Stale v1.0.0 catalog on disk: upgrade/clean immediately
                    JSONObject upgraded = enrichBootstrapCatalog(ctx, doc, true);
                    if (upgraded != null) {
                        writeAtomic(f, upgraded.toString());
                        return upgraded.toString();
                    }
                    f.delete();
                } catch (Throwable ignored) {}
            }
            JSONObject bootstrap = enrichBootstrapCatalog(ctx, null, false);
            return bootstrap != null ? bootstrap.toString() : null;
        }
    }

    private static JSONObject enrichBootstrapCatalog(Context ctx, JSONObject legacyDoc, boolean markOutdated) {
        try {
            Map<String, JSONObject> byKey = new LinkedHashMap<String, JSONObject>();
            Set<String> activeMenuKeys = new LinkedHashSet<String>();
            Set<String> activeMenuPackages = new LinkedHashSet<String>();
            Map<String, JSONObject> menuRecords = new LinkedHashMap<String, JSONObject>();

            // 1. Try reading all_menu if accessible from this process
            Cursor cur = null;
            try {
                cur = ctx.getContentResolver().query(ALL_MENU, null, null, null, null);
                if (cur != null) {
                    int itemsCol = cur.getColumnIndex("menu_items");
                    while (cur.moveToNext()) {
                        String json = itemsCol >= 0 ? cur.getString(itemsCol) : null;
                        if (json == null || json.isEmpty()) continue;
                        JSONObject root = new JSONObject(json);
                        JSONArray items = root.optJSONArray("menuItems");
                        if (items == null) continue;
                        for (int i = 0; i < items.length(); i++) {
                            JSONObject it = items.optJSONObject(i);
                            if (it == null) continue;
                            String pkg = clean(it.optString("packageName", ""));
                            String cls = clean(it.optString("className", ""));
                            int id = it.optInt("itemId", -1);
                            if ("Empty view".equals(pkg) || "Empty view".equals(cls)) continue;
                            if (pkg.isEmpty() && cls.isEmpty() && (id <= 0 || id == 39)) continue;
                            String key = normKey(pkg, cls, id);
                            if (key.isEmpty()) continue;
                            activeMenuKeys.add(key);
                            if (!pkg.isEmpty()) activeMenuPackages.add(pkg);
                            if (!menuRecords.containsKey(key)) menuRecords.put(key, it);
                        }
                    }
                }
            } catch (Throwable ignored) {
            } finally {
                if (cur != null) cur.close();
            }

            // Also preserve any currently hidden keys
            Set<String> hidden = new LinkedHashSet<String>();
            String cfgRaw = readEffectiveConfigJsonLocked(ctx);
            if (cfgRaw != null) {
                try {
                    JSONArray ha = new JSONObject(cfgRaw).optJSONArray("hidden");
                    if (ha != null) {
                        for (int i = 0; i < ha.length(); i++) {
                            String hk = normKeyString(ha.optString(i, ""));
                            if (!hk.isEmpty()) hidden.add(hk);
                        }
                    }
                } catch (Throwable ignored) {}
            }

            boolean hasMenuFilter = !activeMenuKeys.isEmpty();

            // 2. Filter legacy tiles (if any) against equipped all_menu
            if (legacyDoc != null) {
                JSONArray oldArr = legacyDoc.optJSONArray("tiles");
                if (oldArr != null) {
                    for (int i = 0; i < oldArr.length(); i++) {
                        JSONObject row = oldArr.optJSONObject(i);
                        if (row == null) continue;
                        String pkg = clean(row.optString("packageName", ""));
                        String cls = clean(row.optString("className", ""));
                        String key = normKeyString(row.optString("key", ""));
                        if (key.isEmpty()) key = normKey(pkg, cls, -1);
                        if (key.isEmpty()) continue;
                        String kind = "package".equals(row.optString("kind", "")) ? "package" : "system";
                        if ("system".equals(kind) && hasMenuFilter
                                && !activeMenuKeys.contains(key)
                                && !hidden.contains(key)
                                && !((cls.isEmpty() || "*".equals(cls)) && activeMenuPackages.contains(pkg))) {
                            continue;
                        }
                        JSONObject copy = new JSONObject(row.toString());
                        copy.put("key", key);
                        byKey.put(key, copy);
                    }
                }
            }

            // 3. Discover HMG-API app + all enabled HMG-API mod launcher entries
            if (isPackageInstalled(ctx, "hmgapi.app")) {
                String k = "hmgapi.app/hmgapi.app.MainActivity";
                if (!byKey.containsKey(k)) {
                    JSONObject r = new JSONObject();
                    r.put("key", k);
                    r.put("packageName", "hmgapi.app");
                    r.put("className", "hmgapi.app.MainActivity");
                    r.put("title", "HMG-API");
                    r.put("kind", "package");
                    r.put("mod", "hmgapi");
                    byKey.put(k, r);
                }
            }
            if (PUBLIC_STATE.isFile() && PUBLIC_STATE.canRead()) {
                try {
                    JSONObject st = new JSONObject(readUtf8(PUBLIC_STATE));
                    JSONObject mods = st.optJSONObject("mods");
                    if (mods != null) {
                        java.util.Iterator<?> names = mods.keys();
                        while (names.hasNext()) {
                            String modId = String.valueOf(names.next());
                            JSONObject ms = mods.optJSONObject(modId);
                            if (ms == null || !ms.optBoolean("enabled", false)) continue;
                            File mjFile = new File(new File(MODS_ROOT, modId), "mod.json");
                            if (!mjFile.isFile() || !mjFile.canRead()) continue;
                            JSONObject mj = new JSONObject(readUtf8(mjFile));
                            JSONObject prov = mj.optJSONObject("provides");
                            if (prov == null) continue;
                            JSONArray launcher = prov.optJSONArray("launcher");
                            if (launcher == null) continue;
                            for (int i = 0; i < launcher.length(); i++) {
                                JSONObject lm = launcher.optJSONObject(i);
                                if (lm == null) continue;
                                String pkg = clean(lm.optString("package", ""));
                                String cls = clean(lm.optString("class", ""));
                                if (pkg.isEmpty() || !isPackageInstalled(ctx, pkg)) continue;
                                String fullCls = (!pkg.isEmpty() && cls.startsWith(".")) ? pkg + cls : cls;
                                String key = normKey(pkg, fullCls, -1);
                                if (key.isEmpty() || byKey.containsKey(key)) continue;
                                String title = clean(lm.optString("title", ""));
                                if (title.isEmpty()) title = resolveAppLabel(ctx, pkg, fullCls);
                                JSONObject r = new JSONObject();
                                r.put("key", key);
                                r.put("packageName", pkg);
                                r.put("className", fullCls);
                                r.put("title", title);
                                r.put("kind", "package");
                                r.put("mod", modId);
                                byKey.put(key, r);
                            }
                        }
                    }
                } catch (Throwable ignored) {}
            }

            // 4. Add any remaining equipped tiles from all_menu
            for (Map.Entry<String, JSONObject> e : menuRecords.entrySet()) {
                String key = e.getKey();
                if (byKey.containsKey(key)) continue;
                JSONObject it = e.getValue();
                String pkg = clean(it.optString("packageName", ""));
                String cls = clean(it.optString("className", ""));
                int id = it.optInt("itemId", -1);
                String label = resolveAppLabel(ctx, pkg, cls);
                boolean isPkg = !isStockPackage(pkg) || isUserInstalledPkg(ctx, pkg) || id >= 9000 || id == 90;
                JSONObject r = new JSONObject();
                r.put("key", key);
                r.put("packageName", pkg);
                r.put("className", cls);
                r.put("title", isPkg ? label : friendlyTitle(label, pkg, cls));
                r.put("kind", isPkg ? "package" : "system");
                if (pkg.toLowerCase(Locale.US).contains("nemesis") || pkg.toLowerCase(Locale.US).contains("xwtk") || id == 90) {
                    r.put("mod", "nemesis");
                }
                byKey.put(key, r);
            }

            if (byKey.isEmpty()) return null;
            JSONObject out = new JSONObject();
            out.put("schema", CATALOG_SCHEMA);
            if (markOutdated) out.put("hook_outdated", true);
            JSONArray outArr = new JSONArray();
            for (JSONObject row : byKey.values()) outArr.put(row);
            out.put("tiles", outArr);
            return out;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static boolean isPackageInstalled(Context ctx, String pkg) {
        try {
            ctx.getPackageManager().getApplicationInfo(pkg, 0);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean isUserInstalledPkg(Context ctx, String pkg) {
        if (pkg == null || pkg.isEmpty()) return false;
        try {
            ApplicationInfo ai = ctx.getPackageManager().getApplicationInfo(pkg, 0);
            return (ai.flags & ApplicationInfo.FLAG_SYSTEM) == 0;
        } catch (Throwable ignored) {
            return false;
        }
    }

    static boolean isStockPackage(String pkg) {
        String p = clean(pkg).toLowerCase(Locale.US);
        if (p.isEmpty()) return true;
        if (p.contains("nemesis") || p.contains("xwtk") || p.contains("hmgapi")
                || p.contains("leafia") || p.contains("evergreen") || p.startsWith("dev.lemoyne.")) {
            return false;
        }
        return p.startsWith("com.hkmc.") || p.startsWith("com.hyundai.") || p.startsWith("com.kia.")
                || p.startsWith("com.genesis.") || p.startsWith("com.mnsoft.") || p.startsWith("com.daudio.")
                || p.startsWith("com.mobis.") || p.startsWith("com.bul.") || p.startsWith("com.lge.")
                || p.startsWith("com.obigo.") || p.startsWith("com.telechips.") || p.startsWith("com.sdcard.")
                || p.startsWith("com.android.") || p.startsWith("com.google.android.projection.")
                || p.startsWith("com.siriusxm.") || p.startsWith("com.aharadio.")
                || p.startsWith("com.soundhound.") || p.startsWith("com.melodis.")
                || p.startsWith("com.harman.") || p.startsWith("com.qnx.") || p.startsWith("com.nuance.")
                || p.startsWith("com.naver.") || p.startsWith("com.melon.") || p.startsWith("com.genie.")
                || p.startsWith("com.kakao.") || "android".equals(p);
    }

    private static String resolveAppLabel(Context ctx, String pkg, String cls) {
        if (ctx != null && !pkg.isEmpty()) {
            PackageManager pm = ctx.getPackageManager();
            String fullCls = cls.startsWith(".") ? pkg + cls : cls;
            if (!fullCls.isEmpty() && !"*".equals(fullCls)) {
                try {
                    ActivityInfo ai = pm.getActivityInfo(new ComponentName(pkg, fullCls), 0);
                    CharSequence cs = ai.loadLabel(pm);
                    if (cs != null && !cs.toString().trim().isEmpty()) return cs.toString().trim();
                } catch (Throwable ignored) {}
            }
            try {
                ApplicationInfo appInfo = pm.getApplicationInfo(pkg, 0);
                CharSequence cs = pm.getApplicationLabel(appInfo);
                if (cs != null && !cs.toString().trim().isEmpty()) return cs.toString().trim();
            } catch (Throwable ignored) {}
        }
        if (!cls.isEmpty() && !"*".equals(cls)) {
            int dot = cls.lastIndexOf('.');
            return dot >= 0 && dot + 1 < cls.length() ? cls.substring(dot + 1) : cls;
        }
        return !pkg.isEmpty() ? pkg : "Home Tile";
    }

    static String friendlyTitle(String rawLabel, String pkg, String cls) {
        String label = rawLabel == null ? "" : rawLabel.trim();
        boolean technical = label.isEmpty() || label.startsWith("HKMC_") || label.endsWith("_WP")
                || label.endsWith("_WP3") || label.equalsIgnoreCase("QRManual")
                || (label.indexOf('.') > 0 && label.indexOf(' ') < 0) || label.equals(pkg);
        if (!technical) return label;
        String probe = (pkg + "/" + cls + " " + label).toLowerCase(Locale.US);
        if (probe.contains("qrmanual") || probe.contains("webmanual") || probe.contains("usermanual")) return "User's Manual";
        if (probe.contains("carpay") || probe.contains("hyundaipay") || probe.contains("invehiclepay")) return "Hyundai Pay";
        if (probe.contains("bluelink") || probe.contains("telematics")) return "Bluelink";
        if (probe.contains("notification") || probe.contains("noticecenter")) return "Notifications";
        if (probe.contains("mnsoft") || probe.contains("navimenu") || probe.contains(".navi")) return "Navigation Menu";
        if (probe.contains("com.hkmc.ev") || probe.contains(".evactivity")) return "EV";
        if (probe.contains("radio")) return "Radio";
        return !label.isEmpty() ? label : (!pkg.isEmpty() ? pkg : "Home Tile");
    }

    static String readEffectiveConfigJson(Context ctx) {
        synchronized (LOCK) {
            return readEffectiveConfigJsonLocked(ctx);
        }
    }

    private static String readEffectiveConfigJsonLocked(Context ctx) {
        File cfgFile = new File(ctx.getFilesDir(), CONFIG_FILE);
        if (cfgFile.isFile()) {
            try {
                String localRaw = readUtf8(cfgFile);
                JSONObject local = new JSONObject(localRaw);
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

    private static String clean(String s) {
        if (s == null) return "";
        String t = s.trim();
        return "null".equalsIgnoreCase(t) ? "" : t;
    }

    static String normKey(String pkg, String cls) {
        return normKey(pkg, cls, -1);
    }

    static String normKey(String pkg, String cls, int itemId) {
        String p = clean(pkg);
        String c = clean(cls);
        if ("Empty view".equals(p) || "Empty view".equals(c)) return "";
        if (!p.isEmpty()) {
            if (c.isEmpty() || "*".equals(c)) return p + "/*";
            if (c.startsWith(".")) c = p + c;
            return p + "/" + c;
        }
        if (!c.isEmpty() && !"*".equals(c)) return "@class/" + c;
        if (itemId > 0 && itemId != 39) return "@item/" + itemId;
        return "";
    }

    static String normKeyString(String raw) {
        if (raw == null) return "";
        String s = raw.trim();
        int slash = s.indexOf('/');
        if (slash <= 0 || slash + 1 >= s.length()) return "";
        String left = s.substring(0, slash).trim();
        String right = s.substring(slash + 1).trim();
        if ("@class".equals(left)) {
            return right.isEmpty() || "Empty view".equals(right) ? "" : "@class/" + right;
        }
        if ("@item".equals(left)) {
            try {
                int id = Integer.parseInt(right);
                return (id > 0 && id != 39) ? "@item/" + id : "";
            } catch (Exception ignored) {
                return "";
            }
        }
        return normKey(left, right, -1);
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
