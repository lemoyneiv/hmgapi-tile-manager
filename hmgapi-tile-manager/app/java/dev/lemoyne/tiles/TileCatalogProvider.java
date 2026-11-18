package dev.lemoyne.tiles;

import android.content.ComponentName;
import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.FeatureInfo;
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
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Lightweight IPC ContentProvider bridging com.android.launcher3, com.hkmc.system.app.homesetting,
 * and the Tile Manager UI on stock Open-HMG/HMG-API without requiring any core daemon changes:
 *  - putCatalog / getCatalog / refreshCatalog: live schema-5 catalog of equipped stock + custom/mod tiles
 *  - getConfig / putConfig: instant cross-process tile visibility & gap-compaction settings
 */
public final class TileCatalogProvider extends ContentProvider {
    static final int CATALOG_SCHEMA = 5;
    static final String AUTHORITY = "dev.lemoyne.tiles.provider";
    static final Uri URI = Uri.parse("content://" + AUTHORITY);
    static final Uri ALL_MENU = Uri.parse("content://com.hkmc.system.app.homesetting.provider/all_menu");
    static final String ACTION_TILES_CHANGED = "dev.lemoyne.tiles.ACTION_TILES_CHANGED";
    static final String ACTION_REQUEST_CATALOG = "dev.lemoyne.tiles.ACTION_REQUEST_CATALOG";
    static final String ACTION_OPEN_STOCK_EDITOR = "dev.lemoyne.tiles.ACTION_OPEN_STOCK_EDITOR";

    private static final String CATALOG_FILE = "catalog.json";
    private static final String CONFIG_FILE = "config.json";
    private static final String LEGACY_PUBLIC_CONFIG = "/data/hmgapi/public/launcher-tiles.json";
    private static final File PUBLIC_STATE = new File("/data/hmgapi/public/state.json");
    private static final File MODS_ROOT = new File("/data/hmgapi/mods");
    private static final File PERMISSIONS_DIR = new File("/system/etc/permissions");

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
                    // Pre-reboot older hook in launcher3: heal classifications & ghost entries
                    // immediately and mark hook_outdated=true so UI prompts for a reboot.
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

        if ("refreshCatalog".equals(method)) {
            if (!isAllowedCatalogSender(ctx, Binder.getCallingUid())) return null;
            String json = refreshCatalogJson(ctx);
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
                    // Stale schema < 4 catalog on disk: heal classifications, missing ALL_MENU
                    // entries & ghost entries immediately
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

    /**
     * Reconciles the cached catalog against live ALL_MENU + enabled HMG-API mods even when
     * com.android.launcher3 is stopped in the background (e.g. immediately after Show All).
     */
    static String refreshCatalogJson(Context ctx) {
        synchronized (LOCK) {
            File f = new File(ctx.getFilesDir(), CATALOG_FILE);
            JSONObject existingDoc = null;
            String existingRaw = null;
            boolean markOutdated = false;
            if (f.isFile()) {
                try {
                    existingRaw = readUtf8(f);
                    existingDoc = new JSONObject(existingRaw);
                    markOutdated = existingDoc.optInt("schema", 1) < CATALOG_SCHEMA
                            || existingDoc.optBoolean("hook_outdated", false);
                } catch (Throwable ignored) {
                    existingDoc = null;
                }
            }
            JSONObject refreshed = enrichBootstrapCatalog(ctx, existingDoc, markOutdated);
            if (refreshed != null) {
                String out = refreshed.toString();
                try {
                    writeAtomic(f, out);
                } catch (Throwable ignored) {}
                return out;
            }
            return existingRaw;
        }
    }

    private static JSONObject enrichBootstrapCatalog(Context ctx, JSONObject legacyDoc, boolean markOutdated) {
        try {
            Map<String, JSONObject> byKey = new LinkedHashMap<String, JSONObject>();
            Set<String> coveredPackages = new LinkedHashSet<String>();
            Set<String> coveredSystemTitles = new LinkedHashSet<String>();
            Set<String> activeMenuKeys = new LinkedHashSet<String>();
            Set<String> activeMenuPackages = new LinkedHashSet<String>();
            Set<Integer> activeMenuItemIds = new LinkedHashSet<Integer>();
            Map<String, JSONObject> menuRecords = new LinkedHashMap<String, JSONObject>();
            Map<String, JSONObject> nonGuestMenuRecords = new LinkedHashMap<String, JSONObject>();
            Map<String, String> modByPkg = readModPackagesMap();

            // 1. Read ALL_MENU unioning custom-renamed owner profiles + Driver 1 (falling back to
            //    non-Guest profiles if owner profiles have no placed tiles) so unpruned factory
            //    Guest/Driver 2 rows do not inject ghosts while neither owner profile shadows the other.
            List<String> customNamedJsons = new ArrayList<String>();
            List<String> driver1Jsons = new ArrayList<String>();
            List<String> nonGuestJsons = new ArrayList<String>();
            List<String> allJsons = new ArrayList<String>();
            Cursor cur = null;
            try {
                cur = ctx.getContentResolver().query(ALL_MENU, null, null, null, null);
                if (cur != null) {
                    int userCol = cur.getColumnIndex("userName");
                    int itemsCol = cur.getColumnIndex("menu_items");
                    while (cur.moveToNext()) {
                        String user = userCol >= 0 ? clean(cur.getString(userCol)) : "";
                        String json = itemsCol >= 0 ? cur.getString(itemsCol) : null;
                        if (json == null || json.isEmpty()) continue;
                        allJsons.add(json);
                        if ("Guest".equalsIgnoreCase(user)) continue;
                        nonGuestJsons.add(json);
                        if ("Driver 1".equalsIgnoreCase(user) || "Driver1".equalsIgnoreCase(user)
                                || "User 1".equalsIgnoreCase(user) || "User1".equalsIgnoreCase(user)) {
                            driver1Jsons.add(json);
                        } else if (!user.isEmpty()
                                && !"Driver 2".equalsIgnoreCase(user) && !"Driver2".equalsIgnoreCase(user)
                                && !"User 2".equalsIgnoreCase(user) && !"User2".equalsIgnoreCase(user)
                                && !"Driver 3".equalsIgnoreCase(user) && !"Driver3".equalsIgnoreCase(user)
                                && !"User 3".equalsIgnoreCase(user) && !"User3".equalsIgnoreCase(user)) {
                            customNamedJsons.add(json);
                        }
                    }
                }
            } catch (Throwable ignored) {
            } finally {
                if (cur != null) cur.close();
            }

            List<String> ownerJsons = new ArrayList<String>(customNamedJsons.size() + driver1Jsons.size());
            ownerJsons.addAll(customNamedJsons);
            ownerJsons.addAll(driver1Jsons);
            List<String> chosenJsons = (!ownerJsons.isEmpty() && hasPlacedMenuItems(ownerJsons))
                    ? ownerJsons
                    : (!nonGuestJsons.isEmpty() ? nonGuestJsons : allJsons);
            for (String json : chosenJsons) {
                try {
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
                        if (id > 0 && id != 39) activeMenuItemIds.add(Integer.valueOf(id));
                        if (!menuRecords.containsKey(key)) menuRecords.put(key, it);
                    }
                } catch (Throwable ignored) {}
            }

            nonGuestMenuRecords.putAll(menuRecords);
            for (String json : (!nonGuestJsons.isEmpty() ? nonGuestJsons : allJsons)) {
                try {
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
                        if (!key.isEmpty() && !nonGuestMenuRecords.containsKey(key)) {
                            nonGuestMenuRecords.put(key, it);
                        }
                    }
                } catch (Throwable ignored) {}
            }

            // Read currently hidden keys (matching exact, pkg/* wildcard, and empty-class forms)
            // so real hidden tiles backed up in launcher3's private filesDir stay visible
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
            Set<String> multiEntryPackages = new LinkedHashSet<String>();
            multiEntryPackages.add("com.android.settings");

            // 2. Reclassify & filter existing catalog tiles
            if (legacyDoc != null) {
                JSONArray oldArr = legacyDoc.optJSONArray("tiles");
                if (oldArr != null) {
                    Map<String, Integer> pkgKeyCounts = new LinkedHashMap<String, Integer>();
                    for (int i = 0; i < oldArr.length(); i++) {
                        JSONObject row = oldArr.optJSONObject(i);
                        if (row == null) continue;
                        String pkg = clean(row.optString("packageName", ""));
                        if (!pkg.isEmpty()) {
                            Integer prev = pkgKeyCounts.get(pkg);
                            pkgKeyCounts.put(pkg, Integer.valueOf(prev == null ? 1 : prev.intValue() + 1));
                        }
                    }
                    for (Map.Entry<String, Integer> pe : pkgKeyCounts.entrySet()) {
                        if (pe.getValue().intValue() > 1) multiEntryPackages.add(pe.getKey());
                    }

                    for (int i = 0; i < oldArr.length(); i++) {
                        JSONObject row = oldArr.optJSONObject(i);
                        if (row == null) continue;
                        String pkg = clean(row.optString("packageName", ""));
                        String cls = clean(row.optString("className", ""));
                        int itemId = row.optInt("itemId", -1);
                        String key = normKeyString(row.optString("key", ""));
                        if (key.isEmpty()) key = normKey(pkg, cls, itemId);
                        if (key.isEmpty()) continue;
                        String title = clean(row.optString("title", ""));
                        String modId = modByPkg.get(pkg);
                        if (modId == null) {
                            if ("hmgapi.app".equals(pkg) || pkg.startsWith("hmgapi.")) modId = "hmgapi";
                            else if (isNemesisTile(pkg, title, itemId)) modId = "nemesis";
                        }
                        String kind = classifyKind(ctx, pkg, title, itemId, modId);
                        String finalTitle = "system".equals(kind) ? friendlyTitle(title, pkg, cls) : title;
                        if ("system".equals(kind)) {
                            if (!isTargetComponentInstalled(ctx, pkg, cls)) continue;
                            if (!isSupportedStockFeature(ctx, finalTitle, pkg, cls)) continue;
                            if (hasMenuFilter
                                    && !isEquippedInMenu(key, pkg, cls, itemId, activeMenuKeys,
                                            activeMenuPackages, activeMenuItemIds, multiEntryPackages)
                                    && !isUnconditionalCoreStockTile(finalTitle, pkg, cls)
                                    && !isKeyHidden(hidden, key, pkg, cls, itemId)) {
                                continue;
                            }
                        }
                        String iconPkg = clean(row.optString("iconPkg", ""));
                        String iconType = clean(row.optString("iconType", ""));
                        String iconName = clean(row.optString("iconName", ""));
                        int iconId = row.optInt("iconId", 0);
                        putCatalogRow(byKey, coveredPackages, coveredSystemTitles,
                                key, pkg, cls, finalTitle, kind, modId, itemId,
                                iconPkg, iconType, iconName, iconId);
                    }
                }
            }

            // 3. Discover HMG-API app + all enabled HMG-API mod launcher entries
            if (isPackageInstalled(ctx, "hmgapi.app")) {
                putCatalogRow(byKey, coveredPackages, coveredSystemTitles,
                        "hmgapi.app/hmgapi.app.MainActivity", "hmgapi.app",
                        "hmgapi.app.MainActivity", "HMG-API", "package", "hmgapi");
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
                                putCatalogRow(byKey, coveredPackages, coveredSystemTitles,
                                        key, pkg, fullCls, title, "package", modId);
                            }
                        }
                    }
                } catch (Throwable ignored) {}
            }

            // 4. Add remaining equipped tiles from ALL_MENU (recovers stock apps restored via
            //    Show All or missing from an earlier catalog while deduplicating covered packages)
            for (Map.Entry<String, JSONObject> e : menuRecords.entrySet()) {
                String key = e.getKey();
                if (byKey.containsKey(key)) continue;
                JSONObject it = e.getValue();
                String pkg = clean(it.optString("packageName", ""));
                String cls = clean(it.optString("className", ""));
                int id = it.optInt("itemId", -1);
                if (!pkg.isEmpty() && byKey.containsKey(pkg + "/*")) continue;
                if (!pkg.isEmpty() && !multiEntryPackages.contains(pkg) && coveredPackages.contains(pkg)) {
                    continue;
                }
                if ((cls.isEmpty() || "*".equals(cls)) && !pkg.isEmpty() && coveredPackages.contains(pkg)) {
                    continue;
                }
                String label = resolveAppLabel(ctx, pkg, cls);
                String modId = modByPkg.get(pkg);
                if (modId == null) {
                    if ("hmgapi.app".equals(pkg) || pkg.startsWith("hmgapi.")) modId = "hmgapi";
                    else if (isNemesisTile(pkg, label, id)) modId = "nemesis";
                }
                String kind = classifyKind(ctx, pkg, label, id, modId);
                String finalTitle = "package".equals(kind) ? label : friendlyTitle(label, pkg, cls);
                if ("system".equals(kind)) {
                    if (pkg.isEmpty() && !finalTitle.isEmpty()
                            && coveredSystemTitles.contains(finalTitle.toLowerCase(Locale.US))) {
                        continue;
                    }
                    if (!isTargetComponentInstalled(ctx, pkg, cls)) continue;
                    if (!isSupportedStockFeature(ctx, finalTitle, pkg, cls)) continue;
                }
                putCatalogRow(byKey, coveredPackages, coveredSystemTitles,
                        key, pkg, cls, finalTitle, kind, modId, id, "", "", "", 0);
            }

            // 4b. Discover custom/sideloaded package tiles across non-Guest secondary profiles (e.g. Driver 2)
            for (Map.Entry<String, JSONObject> e : nonGuestMenuRecords.entrySet()) {
                String key = e.getKey();
                if (byKey.containsKey(key)) continue;
                JSONObject it = e.getValue();
                String pkg = clean(it.optString("packageName", ""));
                if (pkg.isEmpty() || !isPackageInstalled(ctx, pkg)) continue;
                if (byKey.containsKey(pkg + "/*")) continue;
                if (!multiEntryPackages.contains(pkg) && coveredPackages.contains(pkg)) continue;
                String cls = clean(it.optString("className", ""));
                int id = it.optInt("itemId", -1);
                String label = resolveAppLabel(ctx, pkg, cls);
                String modId = modByPkg.get(pkg);
                if (modId == null) {
                    if ("hmgapi.app".equals(pkg) || pkg.startsWith("hmgapi.")) modId = "hmgapi";
                    else if (isNemesisTile(pkg, label, id)) modId = "nemesis";
                }
                String kind = classifyKind(ctx, pkg, label, id, modId);
                if ("package".equals(kind)) {
                    putCatalogRow(byKey, coveredPackages, coveredSystemTitles,
                            key, pkg, cls, label, kind, modId, id, "", "", "", 0);
                }
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

    private static boolean hasPlacedMenuItems(List<String> menuJsons) {
        if (menuJsons == null) return false;
        for (String json : menuJsons) {
            if (json == null || json.isEmpty()) continue;
            try {
                JSONArray items = new JSONObject(json).optJSONArray("menuItems");
                if (items == null) continue;
                for (int i = 0; i < items.length(); i++) {
                    JSONObject it = items.optJSONObject(i);
                    if (it == null) continue;
                    String pkg = clean(it.optString("packageName", ""));
                    String cls = clean(it.optString("className", ""));
                    int id = it.optInt("itemId", -1);
                    if ("Empty view".equals(pkg) || "Empty view".equals(cls)) continue;
                    if (pkg.isEmpty() && cls.isEmpty() && (id <= 0 || id == 39)) continue;
                    return true;
                }
            } catch (Throwable ignored) {}
        }
        return false;
    }

    private static void putCatalogRow(Map<String, JSONObject> byKey, Set<String> coveredPackages,
                                      Set<String> coveredSystemTitles, String key, String pkg,
                                      String cls, String title, String kind, String modId) throws Exception {
        putCatalogRow(byKey, coveredPackages, coveredSystemTitles,
                key, pkg, cls, title, kind, modId, -1, "", "", "", 0);
    }

    private static void putCatalogRow(Map<String, JSONObject> byKey, Set<String> coveredPackages,
                                      Set<String> coveredSystemTitles, String key, String pkg,
                                      String cls, String title, String kind, String modId, int itemId,
                                      String iconPkg, String iconType, String iconName, int iconId) throws Exception {
        if (key == null || key.isEmpty() || byKey.containsKey(key)) return;
        JSONObject priorWildcard = null;
        if (!pkg.isEmpty()) {
            String wildcard = pkg + "/*";
            if (!key.equals(wildcard) && byKey.containsKey(wildcard)) {
                priorWildcard = byKey.remove(wildcard);
            } else if (key.equals(wildcard) && coveredPackages.contains(pkg)) {
                return;
            }
            coveredPackages.add(pkg);
        }
        if (priorWildcard != null) {
            if ((iconName == null || iconName.isEmpty()) && iconId == 0) {
                iconPkg = clean(priorWildcard.optString("iconPkg", ""));
                iconType = clean(priorWildcard.optString("iconType", ""));
                iconName = clean(priorWildcard.optString("iconName", ""));
                iconId = priorWildcard.optInt("iconId", 0);
            }
            if (itemId <= 0 || itemId == 39) {
                itemId = priorWildcard.optInt("itemId", -1);
            }
        }
        JSONObject row = new JSONObject();
        row.put("key", key);
        row.put("packageName", pkg);
        row.put("className", cls);
        row.put("title", title);
        row.put("kind", kind);
        if (modId != null) row.put("mod", modId);
        if (itemId > 0 && itemId != 39) row.put("itemId", itemId);
        if (iconPkg != null && !iconPkg.isEmpty()) row.put("iconPkg", iconPkg);
        if (iconType != null && !iconType.isEmpty()) row.put("iconType", iconType);
        if (iconName != null && !iconName.isEmpty()) row.put("iconName", iconName);
        if (iconId != 0) row.put("iconId", iconId);
        byKey.put(key, row);
        if ("system".equals(kind) && title != null && !title.isEmpty()) {
            coveredSystemTitles.add(title.toLowerCase(Locale.US));
        }
    }

    private static boolean isKeyHidden(Set<String> hidden, String key, String pkg, String cls, int itemId) {
        if (hidden == null || hidden.isEmpty()) return false;
        if (key != null && !key.isEmpty() && hidden.contains(key)) return true;
        String exact = normKey(pkg, cls, itemId);
        if (!exact.isEmpty() && hidden.contains(exact)) return true;
        if (pkg != null && !pkg.isEmpty()) {
            if (hidden.contains(pkg + "/*")) return true;
            if (cls == null || cls.isEmpty() || "*".equals(cls)) {
                String prefix = pkg + "/";
                for (String hk : hidden) {
                    if (hk.startsWith(prefix)) return true;
                }
            }
        }
        if (itemId > 0 && itemId != 39 && hidden.contains("@item/" + itemId)) return true;
        return false;
    }

    private static boolean isEquippedInMenu(String key, String pkg, String cls, int itemId,
                                            Set<String> activeMenuKeys, Set<String> activeMenuPackages,
                                            Set<Integer> activeMenuItemIds,
                                            Set<String> multiEntryPackages) {
        if (activeMenuKeys.contains(key)) return true;
        if (itemId > 0 && itemId != 39 && activeMenuItemIds != null
                && activeMenuItemIds.contains(Integer.valueOf(itemId))) {
            return true;
        }
        if (!cls.isEmpty() && !"*".equals(cls)) {
            String fullCls = (!pkg.isEmpty() && cls.startsWith(".")) ? pkg + cls : cls;
            if (activeMenuKeys.contains("@class/" + cls) || activeMenuKeys.contains("@class/" + fullCls)) {
                return true;
            }
        }
        if (!pkg.isEmpty()) {
            if (("com.hkmc.radio".equals(pkg) || pkg.startsWith("com.hkmc.radio."))
                    && activeMenuKeys.contains("@class/Radio")) {
                return true;
            }
            boolean isMulti = "com.android.settings".equals(pkg)
                    || (multiEntryPackages != null && multiEntryPackages.contains(pkg));
            if (!isMulti) {
                if (activeMenuKeys.contains(pkg + "/*") || activeMenuPackages.contains(pkg)) return true;
            } else if ("com.android.settings/com.android.settings.Settings".equals(key)
                    && activeMenuKeys.contains("com.android.settings/*")) {
                return true;
            }
        }
        return false;
    }

    private static boolean isUnconditionalCoreStockTile(String title, String pkg, String cls) {
        String t = clean(title).toLowerCase(Locale.US);
        String p = clean(pkg).toLowerCase(Locale.US);
        String c = clean(cls).toLowerCase(Locale.US);
        String fullCls = (!p.isEmpty() && c.startsWith(".")) ? p + c : c;
        String probe = p + "/" + fullCls;
        if (probe.contains("ims") || probe.contains("memoryseat") || probe.contains("seatmemory")
                || probe.contains("seatposition") || probe.contains("soundmood") || probe.contains("moodlamp")
                || probe.contains("dvrs") || probe.contains("builtincam") || probe.contains("vibe")
                || probe.contains("hdradio") || t.contains("hd radio") || t.contains("sirius")) {
            return false;
        }
        if ("com.android.settings".equals(p)) {
            if ("com.android.settings.settings".equals(fullCls)) return true;
            return (fullCls.isEmpty() || "*".equals(fullCls))
                    && ("setup".equals(t) || "settings".equals(t));
        }
        if ("radio".equals(t) || p.equals("com.hkmc.radio") || p.startsWith("com.hkmc.radio.")
                || p.equals("com.daudio.app.radio") || ("radio".equals(c) && p.isEmpty())) {
            return true;
        }
        if ("media".equals(t) || p.equals("com.hkmc.media") || p.startsWith("com.hkmc.media.")
                || p.equals("com.daudio.app.media")) {
            return true;
        }
        if ("phone".equals(t) || p.equals("com.hkmc.phone") || p.startsWith("com.hkmc.phone.")
                || p.equals("com.hkmc.btphone")) {
            return true;
        }
        if ("phone projection".equals(t) || p.contains(".projection")
                || p.equals("com.daudio.app.dipo") || p.equals("com.google.android.projection.sink")) {
            return true;
        }
        if ("voice memo".equals(t) || p.contains("voicememo")) {
            return true;
        }
        if ("user's manual".equals(t) || "users manual".equals(t)
                || p.contains("qrmanual") || p.contains("webmanual") || p.contains("appmanual")) {
            return true;
        }
        if ("notifications".equals(t) || p.contains("notification") || p.contains("noticecenter")) {
            return true;
        }
        if (p.startsWith("com.mnsoft.")
                && ("navigation menu".equals(t) || "navigation".equals(t) || "map".equals(t) || p.equals("com.mnsoft.navi"))) {
            return true;
        }
        return false;
    }

    static Map<String, String> readModPackagesMap() {
        Map<String, String> out = new LinkedHashMap<String, String>();
        if (!PUBLIC_STATE.isFile() || !PUBLIC_STATE.canRead()) return out;
        try {
            JSONObject st = new JSONObject(readUtf8(PUBLIC_STATE));
            JSONObject mods = st.optJSONObject("mods");
            if (mods == null) return out;
            java.util.Iterator<?> names = mods.keys();
            while (names.hasNext()) {
                String modId = String.valueOf(names.next());
                JSONObject ms = mods.optJSONObject(modId);
                if (ms == null) continue;
                JSONArray apps = ms.optJSONArray("apps");
                if (apps != null) {
                    for (int i = 0; i < apps.length(); i++) {
                        String p = clean(apps.optString(i, ""));
                        if (!p.isEmpty()) out.put(p, modId);
                    }
                }
                File mjFile = new File(new File(MODS_ROOT, modId), "mod.json");
                if (mjFile.isFile() && mjFile.canRead()) {
                    try {
                        JSONObject mj = new JSONObject(readUtf8(mjFile));
                        JSONObject prov = mj.optJSONObject("provides");
                        JSONArray launcher = prov != null ? prov.optJSONArray("launcher") : null;
                        if (launcher != null) {
                            for (int i = 0; i < launcher.length(); i++) {
                                JSONObject lm = launcher.optJSONObject(i);
                                String p = lm != null ? clean(lm.optString("package", "")) : "";
                                if (!p.isEmpty()) out.put(p, modId);
                            }
                        }
                    } catch (Throwable ignored) {}
                }
            }
        } catch (Throwable ignored) {}
        return out;
    }

    private static boolean isNemesisTile(String pkg, String title, int itemId) {
        String lowPkg = clean(pkg).toLowerCase(Locale.US);
        String upTitle = title == null ? "" : title.toUpperCase(Locale.US);
        return lowPkg.contains("nemesis") || lowPkg.contains("xwtk")
                || upTitle.contains("NEMESIS") || itemId == 90;
    }

    static String classifyKind(Context ctx, String pkg, String title, int itemId, String modId) {
        String p = clean(pkg);
        boolean isHmgApp = "hmgapi.app".equals(p) || p.startsWith("hmgapi.");
        boolean isNemesis = isNemesisTile(p, title, itemId);
        boolean isUserPkg = isUserInstalledPkg(ctx, p);
        boolean hasMod = modId != null && !modId.trim().isEmpty();
        return (isHmgApp || isNemesis || isUserPkg || hasMod || itemId >= 9000) ? "package" : "system";
    }

    static boolean isGhostSystemKey(Context ctx, String hiddenKey) {
        if (hiddenKey == null || hiddenKey.startsWith("@")) return false;
        int slash = hiddenKey.indexOf('/');
        if (slash <= 0) return false;
        String pkg = hiddenKey.substring(0, slash).trim();
        String cls = hiddenKey.substring(slash + 1).trim();
        if (pkg.isEmpty()) return false;
        if (!isTargetComponentInstalled(ctx, pkg, cls)) return true;
        Map<String, String> mods = readModPackagesMap();
        if ("package".equals(classifyKind(ctx, pkg, "", -1, mods.get(pkg)))) {
            return false;
        }
        String label = resolveAppLabel(ctx, pkg, cls);
        return !isSupportedStockFeature(ctx, label, pkg, cls);
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
        if (ctx == null || pkg == null || pkg.isEmpty()) return false;
        try {
            ApplicationInfo ai = ctx.getPackageManager().getApplicationInfo(pkg, 0);
            return (ai.flags & (ApplicationInfo.FLAG_SYSTEM | ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) == 0;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean isTargetComponentInstalled(Context ctx, String pkg, String cls) {
        String p = clean(pkg);
        if (ctx == null || p.isEmpty()) return true;
        try {
            PackageManager pm = ctx.getPackageManager();
            ApplicationInfo ai = pm.getApplicationInfo(p, 0);
            if (!ai.enabled) return false;
            try {
                int appState = pm.getApplicationEnabledSetting(p);
                if (appState == PackageManager.COMPONENT_ENABLED_STATE_DISABLED || appState == 3) {
                    return false;
                }
            } catch (Throwable ignored) {}

            String c = clean(cls);
            String fullCls = c.startsWith(".") ? p + c : c;
            if (!fullCls.isEmpty() && !"*".equals(fullCls) && fullCls.indexOf('.') > 0) {
                ComponentName cn = new ComponentName(p, fullCls);
                try {
                    int compState = pm.getComponentEnabledSetting(cn);
                    if (compState == PackageManager.COMPONENT_ENABLED_STATE_DISABLED || compState == 3) {
                        return false;
                    }
                } catch (Throwable ignored) {}
                try {
                    ActivityInfo act = pm.getActivityInfo(cn, 0);
                    if (!act.enabled) return false;
                } catch (PackageManager.NameNotFoundException nf) {
                    if ("com.android.settings".equals(p)) return false;
                }
            }
            return true;
        } catch (PackageManager.NameNotFoundException nf) {
            return false;
        } catch (Throwable ignored) {
            return true;
        }
    }

    private static boolean isSupportedStockFeature(Context ctx, String title, String pkg, String cls) {
        String t = clean(title).toLowerCase(Locale.US);
        String p = clean(pkg).toLowerCase(Locale.US);
        String c = clean(cls).toLowerCase(Locale.US);
        String probe = p + "/" + c;

        String product = readSysProp("ro.build.product");
        if (product.isEmpty()) product = readSysProp("ro.product.name");
        String[] parts = product.isEmpty() ? new String[0] : product.split("\\.");
        String model = parts.length > 0 ? parts[0].trim().toUpperCase(Locale.US) : "";
        String region = parts.length > 1 ? parts[1].trim().toUpperCase(Locale.US) : "";
        int buildInfoRegion = readBuildInfoRegion();
        boolean isKorea = "KOR".equals(region) || "KR".equals(region)
                || (region.isEmpty() && (buildInfoRegion < 0 || buildInfoRegion == 0));
        boolean isBaseSe = model.length() > 2 && model.endsWith("SE");

        if ("vibe".equals(t) || p.contains("naver.vibe") || p.endsWith(".vibe") || c.contains(".vibe")) {
            Set<String> feats = readDeviceFeatures(ctx);
            return hasFeatureSub(feats, "vibe") || isKorea;
        }
        if ("built-in cam".equals(t) || "built in cam".equals(t)
                || probe.contains("dvrs") || probe.contains("builtincam") || probe.contains("builtin_cam")) {
            Integer opt = probeServiceOption("dvrs", "builtincam");
            if (opt != null) return opt.intValue() > 0;
            Set<String> feats = readDeviceFeatures(ctx);
            if (hasFeatureSub(feats, "dvrs") || hasFeatureSub(feats, "builtincam")) return true;
            return isKorea;
        }
        if ("sound mood lamp".equals(t) || probe.contains("soundmood") || probe.contains("sound_mood")
                || probe.contains("moodlamp") || probe.contains("mood_lamp")) {
            Integer opt = probeServiceOption("soundmood", "moodlamp", "sml");
            if (opt != null) return opt.intValue() > 0;
            Set<String> feats = readDeviceFeatures(ctx);
            if (hasFeatureSub(feats, "soundmood") || hasFeatureSub(feats, "moodlamp")
                    || hasFeatureToken(feats, "sml")) {
                return true;
            }
            if (isBaseSe || model.startsWith("NEEV") || model.startsWith("CEEV") || model.startsWith("CVEV")
                    || model.startsWith("JW") || model.startsWith("JK") || model.startsWith("GV")
                    || model.startsWith("G80") || model.startsWith("G90")) {
                return false;
            }
            return true;
        }
        if ("memory seat configuration".equals(t) || t.contains("memory seat")
                || probe.contains("memoryseat") || probe.contains("seatmemory")
                || probe.contains("imsseat") || probe.contains(".ims.")
                || c.endsWith("imsactivity") || c.endsWith("imsmainactivity") || c.contains("seatposition")) {
            Integer opt = probeServiceOption("memoryseat", "seatmemory", "ims");
            if (opt != null) return opt.intValue() > 0;
            Set<String> feats = readDeviceFeatures(ctx);
            if (hasFeatureSub(feats, "memoryseat") || hasFeatureSub(feats, "seatmemory")
                    || hasFeatureToken(feats, "ims")) {
                return true;
            }
            if (isBaseSe) return false;
            return true;
        }
        return true;
    }

    private static String readSysProp(String key) {
        try {
            Class<?> sp = Class.forName("android.os.SystemProperties");
            Method m = sp.getMethod("get", String.class, String.class);
            Object v = m.invoke(null, key, "");
            return v != null ? clean(String.valueOf(v)) : "";
        } catch (Throwable ignored) {
            return "";
        }
    }

    private static int readBuildInfoRegion() {
        try {
            Class<?> bi = Class.forName("com.hkmc.BuildInfo");
            Field f = bi.getField("REGION");
            Object v = f.get(null);
            if (v instanceof Number) return ((Number) v).intValue();
        } catch (Throwable ignored) {}
        return -1;
    }

    private static Set<String> readDeviceFeatures(Context ctx) {
        Set<String> out = new LinkedHashSet<String>();
        if (PERMISSIONS_DIR.isDirectory()) {
            File[] files = PERMISSIONS_DIR.listFiles();
            if (files != null) {
                for (File f : files) {
                    if (f == null || !f.isFile() || !f.getName().endsWith(".xml") || !f.canRead()) continue;
                    try {
                        String xml = readUtf8(f);
                        int idx = 0;
                        while ((idx = xml.indexOf("<feature", idx)) >= 0) {
                            int close = xml.indexOf('>', idx);
                            if (close < 0) break;
                            String tag = xml.substring(idx, close);
                            int nPos = tag.indexOf("name=");
                            if (nPos >= 0) {
                                int q1 = nPos + 5;
                                while (q1 < tag.length() && (tag.charAt(q1) == ' ' || tag.charAt(q1) == '\t')) q1++;
                                if (q1 < tag.length() && (tag.charAt(q1) == '"' || tag.charAt(q1) == '\'')) {
                                    char quote = tag.charAt(q1);
                                    int q2 = tag.indexOf(quote, q1 + 1);
                                    if (q2 > q1 + 1) {
                                        out.add(tag.substring(q1 + 1, q2).trim().toLowerCase(Locale.US));
                                    }
                                }
                            }
                            idx = close + 1;
                        }
                    } catch (Throwable ignored) {}
                }
            }
        }
        if (ctx != null) {
            try {
                FeatureInfo[] avail = ctx.getPackageManager().getSystemAvailableFeatures();
                if (avail != null) {
                    for (FeatureInfo fi : avail) {
                        if (fi != null && fi.name != null && !fi.name.trim().isEmpty()) {
                            out.add(fi.name.trim().toLowerCase(Locale.US));
                        }
                    }
                }
            } catch (Throwable ignored) {}
        }
        return out;
    }

    private static boolean hasFeatureSub(Set<String> feats, String sub) {
        for (String f : feats) {
            if (f.contains(sub)) return true;
        }
        return false;
    }

    private static boolean hasFeatureToken(Set<String> feats, String tok) {
        for (String f : feats) {
            if (f.equals(tok) || f.endsWith("." + tok) || f.contains("." + tok + ".")
                    || f.endsWith("_" + tok) || f.contains("_" + tok + "_")) {
                return true;
            }
        }
        return false;
    }

    private static Integer probeServiceOption(String... tokens) {
        String[][] services = new String[][] {
            { "usm", "com.hkmc.usm.IUSMService$Stub" },
            { "system_service", "com.hkmc.system.ISystemService$Stub" },
            { "bcm", "com.hkmc.bcm.IBCMService$Stub" }
        };
        try {
            Class<?> sm = Class.forName("android.os.ServiceManager");
            Method getSvc = sm.getMethod("getService", String.class);
            Class<?> ibinder = Class.forName("android.os.IBinder");
            for (String[] pair : services) {
                try {
                    Object binder = getSvc.invoke(null, pair[0]);
                    if (binder == null) continue;
                    Class<?> stub = Class.forName(pair[1]);
                    Object iface = stub.getMethod("asInterface", ibinder).invoke(null, binder);
                    if (iface == null) continue;
                    for (Method m : iface.getClass().getMethods()) {
                        if (m.getParameterTypes().length != 0 || Modifier.isStatic(m.getModifiers())) continue;
                        Class<?> rt = m.getReturnType();
                        if (rt != boolean.class && rt != Boolean.class && rt != int.class && rt != Integer.class) {
                            continue;
                        }
                        String low = m.getName().toLowerCase(Locale.US);
                        if (!(low.startsWith("issupport") || (low.startsWith("get") && low.endsWith("option")))) {
                            continue;
                        }
                        for (String tok : tokens) {
                            if (low.contains(tok)) {
                                Object res = m.invoke(iface);
                                if (res instanceof Boolean) return Integer.valueOf(((Boolean) res).booleanValue() ? 1 : 0);
                                if (res instanceof Number) return Integer.valueOf(((Number) res).intValue());
                            }
                        }
                    }
                } catch (Throwable ignored) {}
            }
        } catch (Throwable ignored) {}
        return null;
    }

    static String resolveAppLabel(Context ctx, String pkg, String cls) {
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
                        fromState.put("configured", true);
                        writeAtomic(cfgFile, fromState.toString());
                        return fromState.toString();
                    }
                }
                return localRaw;
            } catch (Throwable ignored) {}
        }

        JSONObject fromState = readStateSettingsJson();
        if (fromState != null && fromState.optBoolean("configured", false)) {
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
            try {
                File legacy = new File(LEGACY_PUBLIC_CONFIG);
                if (legacy.isFile()) legacy.delete();
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
            boolean isConfigured = settings.optBoolean("configured", false) || !norm.isEmpty() || !compact;
            JSONObject out = new JSONObject();
            out.put("configured", isConfigured);
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
