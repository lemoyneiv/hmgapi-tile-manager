package dev.lemoyne.tiles.hook;

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;

import hmgapi.hook.LoadedApp;
import hmgapi.hook.MethodHook;
import hmgapi.hook.ModContext;
import hmgapi.hook.ModEntry;
import hmgapi.hook.Points;

import java.io.File;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * HMG-API hook entry for Home Screen Tile Manager on stock Open-HMG/HMG-API:
 *  - com.android.launcher3: records the complete launcher catalog (stock + mod tiles),
 *    pushes it to TileCatalogProvider, filters hidden tiles from the live menu list,
 *    synchronises content://com.hkmc.system.app.homesetting.provider/all_menu with
 *    exact itemId/slot backup & optional gap compaction, and applies changes live.
 *  - com.hkmc.system.app.homesetting: filters hidden tiles out of the Home Screen
 *    Menu Editor and updates live when tile settings change.
 */
public final class TileModEntry extends ModEntry {
    static final String PKG_LAUNCHER = "com.android.launcher3";
    static final String PKG_EDITOR = "com.hkmc.system.app.homesetting";
    static final String ACTION_HMGAPI_STATE = "hmgapi.intent.action.STATE_CHANGED";
    static final Uri ALL_MENU = Uri.parse("content://com.hkmc.system.app.homesetting.provider/all_menu");
    static final Uri CATALOG_URI = Uri.parse("content://" + TileStore.PROVIDER_AUTH);
    static final File PUBLIC_STATE = new File("/data/hmgapi/public/state.json");

    static final class EntryMeta {
        final String key;
        final String pkg;
        final String cls;
        final String title;
        final String kind;
        final String modId;

        EntryMeta(String key, String pkg, String cls, String title, String kind, String modId) {
            this.key = key;
            this.pkg = pkg;
            this.cls = cls;
            this.title = title;
            this.kind = kind;
            this.modId = modId;
        }
    }

    private static final Map<Object, EntryMeta> metaByEntry = new WeakHashMap<Object, EntryMeta>();
    private static final Map<String, String> keyByClassName = new LinkedHashMap<String, String>();
    private static final Object SYNC_LOCK = new Object();
    private static final AtomicBoolean launcherReloadPending = new AtomicBoolean(false);

    private static volatile boolean launcherReceiverStarted;
    private static volatile boolean editorReceiverStarted;
    private static volatile String lastCatalogJson;
    private static volatile String lastAppliedSignature;

    private static ClassLoader editorLoader;
    private static List<Object> liveEditorList;
    private static List<Object> allEditorItems;

    @Override
    public void appLoaded(final ModContext mod, final LoadedApp app) {
        if (PKG_LAUNCHER.equals(app.packageName)) {
            hookLauncher(mod, app);
        } else if (PKG_EDITOR.equals(app.packageName)) {
            hookEditor(mod, app);
        }
    }

    private void hookLauncher(final ModContext mod, final LoadedApp app) {
        for (String id : new String[] {
                "launcher.menu_entry.new", "launcher.menu_list.load",
                "launcher.menu_list.items", "launcher.menu_list.lock", "launcher.menu_list.get_context" }) {
            if (!Points.has(id)) {
                mod.log("launcher tile hooks off on this car (" + id + " not mapped)");
                return;
            }
        }
        final ClassLoader cl = app.classLoader;
        hookEntryCtor(mod, cl, "launcher.menu_entry.new", app.context);

        mod.hook(cl, "launcher.menu_list.load", new MethodHook() {
            @Override
            @SuppressWarnings("unchecked")
            protected void afterHookedMethod(Param p) {
                try {
                    Context ctx = (Context) Points.call(cl, "launcher.menu_list.get_context", p.thisObject);
                    if (ctx == null) ctx = app.context;
                    List<Object> list = (List<Object>) Points.get(cl, "launcher.menu_list.items", p.thisObject);
                    Object lock = Points.get(cl, "launcher.menu_list.lock", p.thisObject);
                    if (list == null || lock == null) return;
                    onLauncherLoaded(mod, ctx, list, lock);
                } catch (Throwable t) {
                    mod.error("launcher.menu_list.load", t);
                }
            }
        });
        mod.log("launcher tile manager active in " + app.processName);
    }

    private void hookEditor(final ModContext mod, final LoadedApp app) {
        for (String id : new String[] {
                "homesetting.edit_entry.new", "homesetting.edit_list.load",
                "homesetting.edit_list.items", "homesetting.edit_list.get_context",
                "homesetting.edit_entry.get_class_name" }) {
            if (!Points.has(id)) {
                mod.log("menu editor tile hooks off on this car (" + id + " not mapped)");
                return;
            }
        }
        final ClassLoader cl = app.classLoader;
        final Field itemsField = Points.field(cl, "homesetting.edit_list.items");
        hookEntryCtor(mod, cl, "homesetting.edit_entry.new", app.context);

        mod.hook(cl, "homesetting.edit_list.load", new MethodHook() {
            @Override
            @SuppressWarnings("unchecked")
            protected void afterHookedMethod(Param p) {
                try {
                    Context ctx = (Context) Points.call(cl, "homesetting.edit_list.get_context", p.thisObject);
                    if (ctx == null) ctx = app.context;
                    List<Object> list = (List<Object>) itemsField.get(null);
                    if (list == null) return;
                    onEditorLoaded(mod, ctx, cl, list);
                } catch (Throwable t) {
                    mod.error("homesetting.edit_list.load", t);
                }
            }
        });
        mod.log("menu editor tile manager active in " + app.processName);
    }

    private static void hookEntryCtor(final ModContext mod, ClassLoader cl, String pointId, final Context fallbackCtx) {
        mod.hook(cl, pointId, new MethodHook() {
            @Override
            protected void afterHookedMethod(Param param) {
                try {
                    if (param.thisObject == null || param.args == null || param.args.length < 7) return;
                    Context ctx = param.args[1] instanceof Context ? (Context) param.args[1] : fallbackCtx;
                    String pkg = param.args[2] == null ? "" : String.valueOf(param.args[2]);
                    String cls = param.args[3] == null ? "" : String.valueOf(param.args[3]);
                    int titleId = param.args[6] instanceof Integer ? ((Integer) param.args[6]).intValue() : 0;
                    EntryMeta meta = buildMeta(ctx, pkg, cls, titleId);
                    synchronized (metaByEntry) {
                        metaByEntry.put(param.thisObject, meta);
                        if (!meta.cls.isEmpty() && !meta.key.isEmpty()) {
                            keyByClassName.put(meta.cls, meta.key);
                        }
                    }
                } catch (Throwable t) {
                    mod.error("tile ctor hook", t);
                }
            }
        });
    }

    private static EntryMeta buildMeta(Context ctx, String pkg, String cls, int titleId) {
        String key = TileStore.normKey(pkg, cls);
        String title = null;
        if (ctx != null && titleId != 0) {
            try {
                CharSequence cs = ctx.getResources().getText(titleId);
                if (cs != null) title = cs.toString();
            } catch (Throwable ignored) {}
        }
        boolean isUserPkg = false;
        if (ctx != null && !pkg.isEmpty()) {
            try {
                PackageManager pm = ctx.getPackageManager();
                String fullCls = cls.startsWith(".") ? pkg + cls : cls;
                if ((title == null || title.trim().isEmpty()) && !fullCls.isEmpty()) {
                    ActivityInfo ai = pm.getActivityInfo(new ComponentName(pkg, fullCls), 0);
                    CharSequence cs = ai.loadLabel(pm);
                    if (cs != null) title = cs.toString();
                }
                ApplicationInfo appInfo = pm.getApplicationInfo(pkg, 0);
                if ((appInfo.flags & ApplicationInfo.FLAG_SYSTEM) == 0) {
                    isUserPkg = true;
                }
            } catch (Throwable ignored) {}
        }
        if (title == null || title.trim().isEmpty()) {
            int dot = cls.lastIndexOf('.');
            title = dot >= 0 && dot + 1 < cls.length() ? cls.substring(dot + 1) : (!cls.isEmpty() ? cls : pkg);
        }
        title = title.replace('\n', ' ').replace('\r', ' ').replaceAll(" +", " ").trim();

        boolean isHmgRes = (titleId & 0xffff0000) == 0x7f7e0000;
        boolean isHmgApp = "hmgapi.app".equals(pkg);
        boolean isNemesis = "com.nemesis.manager".equals(pkg)
                || pkg.startsWith("com.nemesis.")
                || title.toUpperCase(Locale.US).contains("NEMESIS");
        String modId = resolveModIdForPackage(pkg);
        if (modId == null) {
            if (isHmgApp) modId = "hmgapi";
            else if (isNemesis) modId = "nemesis";
        }
        String kind = (isHmgRes || isHmgApp || isNemesis || isUserPkg || modId != null) ? "package" : "system";
        return new EntryMeta(key, pkg, cls, title, kind, modId);
    }

    @SuppressWarnings("unchecked")
    private static String resolveModIdForPackage(String pkg) {
        if (pkg == null || pkg.isEmpty() || !PUBLIC_STATE.isFile()) return null;
        try {
            Map<String, Object> root = TileStore.parseObject(TileStore.readUtf8(PUBLIC_STATE));
            Object modsObj = root.get("mods");
            if (!(modsObj instanceof Map)) return null;
            for (Map.Entry<String, Object> e : ((Map<String, Object>) modsObj).entrySet()) {
                if (!(e.getValue() instanceof Map)) continue;
                Object appsObj = ((Map<String, Object>) e.getValue()).get("apps");
                if (appsObj instanceof List) {
                    for (Object a : (List<Object>) appsObj) {
                        if (pkg.equals(a)) return e.getKey();
                    }
                }
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private static EntryMeta metaOf(Object entry) {
        synchronized (metaByEntry) {
            return metaByEntry.get(entry);
        }
    }

    private static void onLauncherLoaded(ModContext mod, Context ctx, List<Object> list, Object lock) {
        if (ctx != null) startLauncherReceiver(mod, ctx);

        Map<String, Map<String, Object>> catalogByKey = new LinkedHashMap<String, Map<String, Object>>();
        synchronized (lock) {
            for (Object item : list) {
                EntryMeta m = metaOf(item);
                if (m == null || m.key.isEmpty() || catalogByKey.containsKey(m.key)) continue;
                Map<String, Object> row = new LinkedHashMap<String, Object>();
                row.put("key", m.key);
                row.put("packageName", m.pkg);
                row.put("className", m.cls);
                row.put("title", m.title);
                row.put("kind", m.kind);
                if (m.modId != null) row.put("mod", m.modId);
                catalogByKey.put(m.key, row);
            }
        }

        Set<String> installedKeys = new LinkedHashSet<String>(catalogByKey.keySet());
        if (!catalogByKey.isEmpty()) {
            Map<String, Object> doc = new LinkedHashMap<String, Object>();
            doc.put("tiles", new ArrayList<Object>(catalogByKey.values()));
            String catJson = TileStore.writeCompact(doc);
            lastCatalogJson = catJson;
            pushCatalogAsync(ctx, catJson);
        }

        TileStore.Config cfg = effectiveConfig(mod, ctx, null);
        lastAppliedSignature = cfg.signature(mod.enabled());

        if (!cfg.hidden.isEmpty()) {
            int removed = 0;
            synchronized (lock) {
                for (Iterator<Object> it = list.iterator(); it.hasNext();) {
                    EntryMeta m = metaOf(it.next());
                    if (m != null && !m.key.isEmpty() && cfg.hidden.contains(m.key)) {
                        it.remove();
                        removed++;
                    }
                }
            }
            if (removed > 0) mod.log("launcher: hid " + removed + " tile(s)");
        }

        if (ctx != null) {
            syncSavedMenu(mod, ctx, cfg, installedKeys.isEmpty() ? null : installedKeys);
        }
    }

    private static void pushCatalogAsync(final Context ctx, final String catJson) {
        if (ctx == null || catJson == null) return;
        new Thread("hmgapi-tiles-catalog") {
            @Override
            public void run() {
                try {
                    ctx.getContentResolver().call(CATALOG_URI, "putCatalog", catJson, null);
                } catch (Throwable ignored) {
                    // TileManager.apk not installed yet or starting up; ACTION_REQUEST_CATALOG retries on open
                }
            }
        }.start();
    }

    private static void onEditorLoaded(ModContext mod, Context ctx, ClassLoader cl, List<Object> list) {
        if (ctx != null) startEditorReceiver(mod, ctx);
        synchronized (list) {
            editorLoader = cl;
            liveEditorList = list;
            allEditorItems = new ArrayList<Object>(list);
            filterEditorLocked(mod, ctx, cl, list, null);
        }
    }

    private static void filterEditorLocked(ModContext mod, Context ctx, ClassLoader cl, List<Object> list, String overrideJson) {
        if (allEditorItems == null) return;
        list.clear();
        TileStore.Config cfg = effectiveConfig(mod, ctx, overrideJson);
        int removed = 0;
        for (Object item : allEditorItems) {
            EntryMeta m = metaOf(item);
            String key = m != null ? m.key : "";
            if (key.isEmpty() && cl != null) {
                try {
                    String cls = (String) Points.call(cl, "homesetting.edit_entry.get_class_name", item);
                    if (cls != null) {
                        synchronized (metaByEntry) {
                            String k = keyByClassName.get(cls);
                            if (k != null) key = k;
                        }
                    }
                } catch (Throwable ignored) {}
            }
            if (!key.isEmpty() && cfg.hidden.contains(key)) {
                removed++;
            } else {
                list.add(item);
            }
        }
        if (removed > 0) mod.log("editor: hid " + removed + " tile(s)");
    }

    private static TileStore.Config effectiveConfig(ModContext mod, Context ctx, String overrideJson) {
        if (mod == null || !mod.enabled()) {
            return new TileStore.Config(true, false, Collections.<String>emptySet());
        }
        if (overrideJson != null && !overrideJson.isEmpty()) {
            try {
                return TileStore.parseConfig(overrideJson, true);
            } catch (Throwable ignored) {}
        }
        // 1. Live provider config (instant cross-process sync from TileManager UI)
        if (ctx != null) {
            try {
                Bundle b = ctx.getContentResolver().call(CATALOG_URI, "getConfig", null, null);
                if (b != null) {
                    String json = b.getString("config_json");
                    if (json != null && !json.isEmpty()) {
                        TileStore.Config c = TileStore.parseConfig(json, true);
                        if (c.exists) return c;
                    }
                }
            } catch (Throwable ignored) {}
        }
        // 2. HMG-API live mod settings (settings/hmgapi-tile-manager.json -> public/state.json)
        String rawHidden = mod.settingString("hidden_keys", "");
        boolean hasHidden = rawHidden != null && !rawHidden.trim().isEmpty();
        boolean configuredFlag = mod.settingBoolean("configured", false);
        boolean compact = mod.settingBoolean("compact_gaps", true);
        if (configuredFlag || hasHidden || !compact) {
            Set<String> hidden = new LinkedHashSet<String>();
            if (rawHidden != null && !rawHidden.isEmpty()) {
                for (String part : rawHidden.split(",")) {
                    String k = TileStore.normKeyString(part);
                    if (!k.isEmpty()) hidden.add(k);
                }
            }
            return new TileStore.Config(true, compact, hidden);
        }
        // 3. Automatic migration from legacy lemoyneiv/HMG-API public/launcher-tiles.json
        File legacy = new File(TileStore.LEGACY_PUBLIC_CONFIG);
        if (legacy.isFile()) {
            TileStore.Config c = TileStore.readFileConfig(legacy);
            if (c.exists) return c;
        }
        return new TileStore.Config(false, compact, Collections.<String>emptySet());
    }

    private static void startLauncherReceiver(final ModContext mod, Context ctx) {
        if (launcherReceiverStarted) return;
        synchronized (TileModEntry.class) {
            if (launcherReceiverStarted) return;
            final Context appCtx = ctx.getApplicationContext() != null ? ctx.getApplicationContext() : ctx;
            IntentFilter f = new IntentFilter();
            f.addAction(TileStore.ACTION_TILES_CHANGED);
            f.addAction(TileStore.ACTION_REQUEST_CATALOG);
            f.addAction(ACTION_HMGAPI_STATE);
            appCtx.registerReceiver(new BroadcastReceiver() {
                @Override
                public void onReceive(Context c, Intent intent) {
                    String action = intent == null ? null : intent.getAction();
                    if (TileStore.ACTION_REQUEST_CATALOG.equals(action)) {
                        pushCatalogAsync(appCtx, lastCatalogJson);
                        return;
                    }
                    final String overrideJson = intent != null ? intent.getStringExtra("config_json") : null;
                    final TileStore.Config nextCfg = effectiveConfig(mod, appCtx, overrideJson);
                    final String nextSig = nextCfg.signature(mod.enabled());
                    if (ACTION_HMGAPI_STATE.equals(action) && nextSig.equals(lastAppliedSignature)) {
                        return; // Unrelated mod setting change — do not restart launcher!
                    }
                    if (!launcherReloadPending.compareAndSet(false, true)) return;
                    new Thread("hmgapi-tiles-sync") {
                        @Override
                        public void run() {
                            try {
                                Thread.sleep(150);
                                TileStore.Config fresh = effectiveConfig(mod, appCtx, overrideJson);
                                lastAppliedSignature = fresh.signature(mod.enabled());
                                syncSavedMenu(mod, appCtx, fresh, installedCatalogKeys(appCtx));
                            } catch (Throwable t) {
                                mod.error("launcher live tile sync", t);
                            } finally {
                                launcherReloadPending.set(false);
                            }
                            mod.log("launcher: restarting process to apply tile visibility changes");
                            android.os.Process.killProcess(android.os.Process.myPid());
                        }
                    }.start();
                }
            }, f);
            launcherReceiverStarted = true;
        }
    }

    private static void startEditorReceiver(final ModContext mod, Context ctx) {
        if (editorReceiverStarted) return;
        synchronized (TileModEntry.class) {
            if (editorReceiverStarted) return;
            final Context appCtx = ctx.getApplicationContext() != null ? ctx.getApplicationContext() : ctx;
            IntentFilter f = new IntentFilter();
            f.addAction(TileStore.ACTION_TILES_CHANGED);
            f.addAction(ACTION_HMGAPI_STATE);
            appCtx.registerReceiver(new BroadcastReceiver() {
                @Override
                public void onReceive(Context c, Intent intent) {
                    List<Object> list = liveEditorList;
                    if (list == null) return;
                    String overrideJson = intent != null ? intent.getStringExtra("config_json") : null;
                    synchronized (list) {
                        try {
                            filterEditorLocked(mod, appCtx, editorLoader, list, overrideJson);
                        } catch (Throwable t) {
                            mod.error("editor live tile filter", t);
                        }
                    }
                }
            }, f);
            editorReceiverStarted = true;
        }
    }

    @SuppressWarnings("unchecked")
    private static Set<String> installedCatalogKeys(Context ctx) {
        String cat = lastCatalogJson;
        if (cat == null || cat.isEmpty()) return null;
        try {
            Map<String, Object> doc = TileStore.parseObject(cat);
            Object raw = doc.get("tiles");
            if (!(raw instanceof List)) return null;
            Set<String> out = new LinkedHashSet<String>();
            PackageManager pm = ctx != null ? ctx.getPackageManager() : null;
            for (Object item : (List<Object>) raw) {
                if (!(item instanceof Map)) continue;
                Map<String, Object> row = (Map<String, Object>) item;
                String pkg = TileStore.str(row.get("packageName"));
                String cls = TileStore.str(row.get("className"));
                String key = TileStore.normKey(pkg, cls);
                if (key.isEmpty()) continue;
                if (pm != null && !pkg.isEmpty() && !cls.isEmpty()) {
                    String fullCls = cls.startsWith(".") ? pkg + cls : cls;
                    try {
                        pm.getActivityInfo(new ComponentName(pkg, fullCls), 0);
                        out.add(key);
                    } catch (PackageManager.NameNotFoundException ignored) {}
                } else {
                    out.add(key);
                }
            }
            return out;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static void syncSavedMenu(ModContext mod, Context ctx, TileStore.Config cfg, Set<String> installedKeys) {
        synchronized (SYNC_LOCK) {
            ContentResolver cr = ctx.getContentResolver();
            Cursor cur = null;
            try {
                File bakFile = new File(ctx.getFilesDir(), TileStore.BACKUP_FILE);
                File legacyBak = new File(ctx.getFilesDir(), TileStore.LEGACY_BACKUP_FILE);
                Map<String, Object> backups = new LinkedHashMap<String, Object>();
                File sourceBak = bakFile.isFile() ? bakFile : (legacyBak.isFile() ? legacyBak : null);
                if (sourceBak != null) {
                    try {
                        backups = TileStore.parseObject(TileStore.readUtf8(sourceBak));
                    } catch (Throwable ignored) {
                        backups = new LinkedHashMap<String, Object>();
                    }
                }

                cur = cr.query(ALL_MENU, null, null, null, null);
                if (cur == null) return;
                int userCol = cur.getColumnIndex("userName");
                int itemsCol = cur.getColumnIndex("menu_items");
                boolean backupChanged = (sourceBak == legacyBak);
                while (cur.moveToNext()) {
                    String user = cur.getString(userCol);
                    String json = cur.getString(itemsCol);
                    if (user == null || json == null) continue;
                    TileStore.SyncResult res = TileStore.syncProfileMenu(user, json, cfg, installedKeys, backups);
                    for (String line : res.logs) mod.log(line);
                    if (res.backupChanged) backupChanged = true;
                    if (res.updatedMenuJson != null) {
                        ContentValues v = new ContentValues();
                        v.put("menu_items", res.updatedMenuJson);
                        cr.update(ALL_MENU, v, "userName=?", new String[] { user });
                    }
                }
                if (backupChanged) {
                    TileStore.writeAtomic(bakFile, TileStore.writeCompact(backups));
                }
            } catch (Throwable t) {
                mod.error("saved menu sync", t);
            } finally {
                if (cur != null) cur.close();
            }
        }
    }
}
