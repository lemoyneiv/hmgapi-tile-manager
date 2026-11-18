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
import android.content.pm.FeatureInfo;
import android.content.pm.PackageManager;
import android.content.res.Resources;
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
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * HMG-API hook entry for Home Screen Tile Manager on stock Open-HMG/HMG-API:
 *  - com.android.launcher3: inspects all menu entries (7-arg + overloaded constructors), wraps
 *    launcher.menu_list.items in FilterableMenuList so mod/custom tiles added later by HMG-API's
 *    LIFO afterHookedMethod or NEMESIS are captured and filtered, excludes unequipped trim/region
 *    ghost stock tiles via PackageManager + Gen5wFacts + all_menu, pushes the schema-4 catalog to
 *    TileCatalogProvider, and synchronises all_menu with exact itemId/slot backup & gap compaction.
 *  - com.hkmc.system.app.homesetting: wraps homesetting.edit_list.items in FilterableMenuList so
 *    both stock and mod/custom hidden tiles stay hidden in Edit Home Icons and update live.
 */
public final class TileModEntry extends ModEntry {
    static final String PKG_LAUNCHER = "com.android.launcher3";
    static final String PKG_EDITOR = "com.hkmc.system.app.homesetting";
    static final String ACTION_HMGAPI_STATE = "hmgapi.intent.action.STATE_CHANGED";
    static final Uri ALL_MENU = Uri.parse("content://com.hkmc.system.app.homesetting.provider/all_menu");
    static final Uri CATALOG_URI = Uri.parse("content://" + TileStore.PROVIDER_AUTH);
    static final File PUBLIC_STATE = new File("/data/hmgapi/public/state.json");
    static final File MODS_ROOT = new File("/data/hmgapi/mods");
    static final File PERMISSIONS_DIR = new File("/system/etc/permissions");

    static final class EntryMeta {
        final String key;
        final String pkg;
        final String cls;
        final String title;
        final String kind;
        final String modId;
        final int itemId;

        EntryMeta(String key, String pkg, String cls, String title, String kind, String modId, int itemId) {
            this.key = key;
            this.pkg = pkg;
            this.cls = cls;
            this.title = title;
            this.kind = kind;
            this.modId = modId;
            this.itemId = itemId;
        }
    }

    private static final Map<Object, EntryMeta> metaByEntry = new WeakHashMap<Object, EntryMeta>();
    private static final Map<String, String> keyByClassName = new LinkedHashMap<String, String>();
    private static final TileStore.EntryInspector launcherInspector = new TileStore.EntryInspector();
    private static final TileStore.EntryInspector editorInspector = new TileStore.EntryInspector();

    private static final Object SYNC_LOCK = new Object();
    private static final AtomicBoolean launcherReloadPending = new AtomicBoolean(false);
    private static final AtomicInteger postLoadSeq = new AtomicInteger(0);

    private static volatile boolean launcherReceiverStarted;
    private static volatile boolean editorReceiverStarted;
    private static volatile String lastCatalogJson;
    private static volatile String lastAppliedSignature;
    private static volatile TileStore.Gen5wFacts cachedFacts;

    private static volatile TileStore.FilterableMenuList liveLauncherList;
    private static volatile Object liveLauncherLock;

    private static volatile ClassLoader editorLoader;
    private static volatile TileStore.FilterableMenuList liveEditorList;

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
        final Field itemsField = Points.field(cl, "launcher.menu_list.items");
        if (app.context != null) {
            startLauncherReceiver(mod, app.context);
        }
        hookEntryCtor(mod, cl, "launcher.menu_entry.new", app.context, launcherInspector);

        mod.hook(cl, "launcher.menu_list.load", new MethodHook() {
            @Override
            @SuppressWarnings("unchecked")
            protected void afterHookedMethod(Param p) {
                try {
                    Context ctx = (Context) Points.call(cl, "launcher.menu_list.get_context", p.thisObject);
                    if (ctx == null) ctx = app.context;
                    List<Object> list = (List<Object>) itemsField.get(p.thisObject);
                    Object lock = Points.get(cl, "launcher.menu_list.lock", p.thisObject);
                    if (list == null || lock == null) return;
                    onLauncherLoaded(mod, ctx, p.thisObject, itemsField, list, lock);
                } catch (Throwable t) {
                    mod.error("launcher.menu_list.load", t);
                }
            }
        });
        mod.log("launcher tile manager v1.0.3 active in " + app.processName);
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
        if (app.context != null) {
            startEditorReceiver(mod, app.context);
        }
        hookEntryCtor(mod, cl, "homesetting.edit_entry.new", app.context, editorInspector);

        mod.hook(cl, "homesetting.edit_list.load", new MethodHook() {
            @Override
            @SuppressWarnings("unchecked")
            protected void afterHookedMethod(Param p) {
                try {
                    Context ctx = (Context) Points.call(cl, "homesetting.edit_list.get_context", p.thisObject);
                    if (ctx == null) ctx = app.context;
                    List<Object> list = (List<Object>) itemsField.get(null);
                    if (list == null) return;
                    onEditorLoaded(mod, ctx, cl, itemsField, list);
                } catch (Throwable t) {
                    mod.error("homesetting.edit_list.load", t);
                }
            }
        });
        mod.log("menu editor tile manager v1.0.3 active in " + app.processName);
    }

    private static void hookEntryCtor(final ModContext mod, ClassLoader cl, String pointId,
                                      final Context fallbackCtx, final TileStore.EntryInspector inspector) {
        mod.hook(cl, pointId, new MethodHook() {
            @Override
            protected void afterHookedMethod(Param param) {
                try {
                    if (param.thisObject == null || param.args == null || param.args.length < 7) return;
                    Context ctx = param.args[1] instanceof Context ? (Context) param.args[1] : fallbackCtx;
                    String pkg = TileStore.cleanToken(param.args[2]);
                    String cls = TileStore.cleanToken(param.args[3]);
                    int iconId = param.args[4] instanceof Integer ? ((Integer) param.args[4]).intValue() : 0;
                    int iconDisId = param.args[5] instanceof Integer ? ((Integer) param.args[5]).intValue() : 0;
                    int titleId = param.args[6] instanceof Integer ? ((Integer) param.args[6]).intValue() : 0;
                    EntryMeta meta = buildMeta(ctx, pkg, cls, titleId, null, -1);
                    inspector.calibrate(param.thisObject, pkg, cls, iconId, iconDisId, titleId, meta.title);
                    cacheMeta(param.thisObject, meta);
                } catch (Throwable t) {
                    mod.error("tile ctor hook", t);
                }
            }
        });
    }

    private static void cacheMeta(Object entry, EntryMeta meta) {
        if (entry == null || meta == null) return;
        synchronized (metaByEntry) {
            metaByEntry.put(entry, meta);
            if (!meta.cls.isEmpty() && !"*".equals(meta.cls) && !meta.key.isEmpty()) {
                keyByClassName.put(meta.cls, meta.key);
                keyByClassName.put(TileStore.expandClass(meta.pkg, meta.cls), meta.key);
            }
        }
    }

    private static EntryMeta metaOf(Object entry) {
        if (entry == null) return null;
        synchronized (metaByEntry) {
            return metaByEntry.get(entry);
        }
    }

    private static EntryMeta ensureMeta(Context ctx, Object entry, TileStore.EntryInspector inspector,
                                        TileStore.MenuInventory inv, Set<String> alreadyMatchedKeys,
                                        ClassLoader editorCl) {
        EntryMeta cached = metaOf(entry);
        if (cached != null && !cached.key.isEmpty()) return cached;
        if (entry == null) return null;

        TileStore.EntryInspector.Extracted ex = inspector.extract(
                entry, inv, alreadyMatchedKeys, resourceStringProbe(ctx));
        String pkg = ex != null ? ex.pkg : "";
        String cls = ex != null ? ex.cls : "";
        int titleId = ex != null ? ex.titleId : 0;
        String titleText = ex != null ? ex.titleText : "";
        int itemId = ex != null ? ex.matchedItemId : -1;

        if (cls.isEmpty() && editorCl != null) {
            try {
                String c = TileStore.cleanToken(Points.call(editorCl, "homesetting.edit_entry.get_class_name", entry));
                if (!c.isEmpty()) {
                    cls = c;
                    if (pkg.isEmpty() && inv != null) {
                        String mappedPkg = inv.classToPackage.get(c);
                        if (mappedPkg != null) pkg = mappedPkg;
                    }
                }
            } catch (Throwable ignored) {}
        }

        if (pkg.isEmpty() && !cls.isEmpty()) {
            synchronized (metaByEntry) {
                String knownKey = keyByClassName.get(cls);
                if (knownKey != null) {
                    int slash = knownKey.indexOf('/');
                    if (slash > 0 && !knownKey.startsWith("@")) {
                        pkg = knownKey.substring(0, slash);
                    }
                }
            }
        }

        EntryMeta built = buildMeta(ctx, pkg, cls, titleId, titleText, itemId);
        if (!built.key.isEmpty()) {
            cacheMeta(entry, built);
        }
        return built;
    }

    private static TileStore.IntStringProbe resourceStringProbe(final Context ctx) {
        if (ctx == null) return null;
        return new TileStore.IntStringProbe() {
            @Override
            public String resolveStringResource(int resId) {
                if (resId == 0) return null;
                try {
                    Resources res = ctx.getResources();
                    if ((resId & 0xffff0000) == 0x7f7e0000) {
                        if (((resId - 0x7f7e0000) % 3) != 0) return null;
                        CharSequence cs = res.getText(resId);
                        return cs != null ? cs.toString() : null;
                    }
                    if (!"string".equals(res.getResourceTypeName(resId))) return null;
                    CharSequence cs = res.getText(resId);
                    return cs != null ? cs.toString() : null;
                } catch (Throwable ignored) {
                    return null;
                }
            }
        };
    }

    private static EntryMeta buildMeta(Context ctx, String rawPkg, String rawCls,
                                       int titleId, String fallbackTitleText, int itemId) {
        String pkg = TileStore.cleanToken(rawPkg);
        String cls = TileStore.cleanToken(rawCls);
        String key = TileStore.normKey(pkg, cls, itemId);
        String title = null;

        if (ctx != null && titleId != 0) {
            try {
                CharSequence cs = ctx.getResources().getText(titleId);
                if (cs != null) title = cs.toString();
            } catch (Throwable ignored) {}
        }
        if ((title == null || title.trim().isEmpty()) && fallbackTitleText != null && !fallbackTitleText.trim().isEmpty()) {
            title = fallbackTitleText.trim();
        }

        TileStore.ModLauncherSpec modSpec = findModLauncherSpec(pkg, cls);
        if ((title == null || title.trim().isEmpty()) && modSpec != null && !modSpec.title.isEmpty()) {
            title = modSpec.title;
        }

        boolean isUserPkg = false;
        if (ctx != null && !pkg.isEmpty()) {
            try {
                PackageManager pm = ctx.getPackageManager();
                String fullCls = TileStore.expandClass(pkg, cls);
                if ((title == null || title.trim().isEmpty()) && !fullCls.isEmpty() && !"*".equals(fullCls)) {
                    try {
                        ActivityInfo ai = pm.getActivityInfo(new ComponentName(pkg, fullCls), 0);
                        CharSequence cs = ai.loadLabel(pm);
                        if (cs != null) title = cs.toString();
                    } catch (Throwable ignored) {}
                }
                ApplicationInfo appInfo = pm.getApplicationInfo(pkg, 0);
                if ((appInfo.flags & (ApplicationInfo.FLAG_SYSTEM | ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) == 0) {
                    isUserPkg = true;
                }
                if (title == null || title.trim().isEmpty()) {
                    CharSequence appLabel = pm.getApplicationLabel(appInfo);
                    if (appLabel != null) title = appLabel.toString();
                }
            } catch (Throwable ignored) {}
        }

        if (title == null) title = "";
        title = title.replace('\n', ' ').replace('\r', ' ').replaceAll(" +", " ").trim();

        boolean isHmgApp = "hmgapi.app".equals(pkg) || pkg.startsWith("hmgapi.");
        String lowPkg = pkg.toLowerCase(Locale.US);
        boolean isNemesis = lowPkg.contains("nemesis") || lowPkg.contains("xwtk")
                || title.toUpperCase(Locale.US).contains("NEMESIS")
                || itemId == 90;

        String modId = modSpec != null ? modSpec.modId : resolveModIdForPackage(pkg);
        if (modId == null) {
            if (isHmgApp) modId = "hmgapi";
            else if (isNemesis) modId = "nemesis";
        }

        String kind = TileStore.classifyTileKind(pkg, title, titleId, itemId, isUserPkg, modId);

        if ("hmgapi".equals(modId) && (title.isEmpty() || title.equals(pkg))) {
            title = "HMG-API";
        } else if ("system".equals(kind) && titleId == 0) {
            title = TileStore.friendlySystemTitle(title, pkg, cls);
        } else if (title.isEmpty()) {
            if (!cls.isEmpty() && !"*".equals(cls)) {
                int dot = cls.lastIndexOf('.');
                title = dot >= 0 && dot + 1 < cls.length() ? cls.substring(dot + 1) : cls;
            } else if (!pkg.isEmpty()) {
                title = pkg;
            } else {
                title = "Home Tile";
            }
        }

        return new EntryMeta(key, pkg, cls, title, kind, modId, itemId);
    }

    private static TileStore.ModLauncherSpec findModLauncherSpec(String pkg, String cls) {
        if (pkg == null || pkg.isEmpty()) return null;
        String targetKey = TileStore.normKey(pkg, cls);
        for (TileStore.ModLauncherSpec spec : TileStore.readHmgApiLauncherMods(PUBLIC_STATE, MODS_ROOT)) {
            if (targetKey.equals(spec.key) || ("*".equals(cls) || cls.isEmpty()) && pkg.equals(spec.packageName)) {
                return spec;
            }
        }
        return null;
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

    private static void preSeedMatchedKeys(Iterable<?> items, Set<String> matchedKeys) {
        if (items == null || matchedKeys == null) return;
        for (Object item : items) {
            EntryMeta m = metaOf(item);
            if (m != null && !m.key.isEmpty()) matchedKeys.add(m.key);
        }
    }

    private static void onLauncherLoaded(final ModContext mod, final Context ctx, final Object menuListOwner,
                                         final Field itemsField, List<Object> list, final Object lock) {
        if (ctx != null) startLauncherReceiver(mod, ctx);

        final TileStore.MenuInventory initialInv = readMenuInventory(ctx);
        final TileStore.Config initialCfg = effectiveConfig(mod, ctx, null);
        lastAppliedSignature = initialCfg.signature(mod.enabled());

        final AtomicBoolean postHooksActive = new AtomicBoolean(false);
        final Set<String> matchedKeys = new LinkedHashSet<String>();
        synchronized (lock) {
            preSeedMatchedKeys(list, matchedKeys);
        }

        TileStore.ItemVisibilityFilter filter = new TileStore.ItemVisibilityFilter() {
            @Override
            public void onItemAdded(Object item) {
                EntryMeta m = ensureMeta(ctx, item, launcherInspector, initialInv, matchedKeys, null);
                if (m != null && !m.key.isEmpty()) {
                    matchedKeys.add(m.key);
                }
                if (postHooksActive.get()) {
                    schedulePostLoadFinalize(mod, ctx);
                }
            }

            @Override
            public boolean shouldShow(Object item) {
                if (!mod.enabled()) return true;
                EntryMeta m = ensureMeta(ctx, item, launcherInspector, initialInv, matchedKeys, null);
                if (m == null || m.key.isEmpty()) return true;
                return TileStore.matchingHiddenKey(initialCfg.hidden, m.pkg, m.cls, m.itemId) == null;
            }
        };

        TileStore.FilterableMenuList managed;
        int hiddenNow;
        synchronized (lock) {
            if (list instanceof TileStore.FilterableMenuList) {
                managed = (TileStore.FilterableMenuList) list;
                hiddenNow = managed.resetFrom(list, filter);
            } else {
                managed = new TileStore.FilterableMenuList(list, filter);
                hiddenNow = list.size() - managed.size();
                try {
                    itemsField.set(menuListOwner, managed);
                } catch (Throwable t) {
                    mod.error("wrap launcher.menu_list.items", t);
                }
            }
            liveLauncherList = managed;
            liveLauncherLock = lock;
        }
        if (hiddenNow > 0) {
            mod.log("launcher: hid " + hiddenNow + " tile(s) from menu list");
        }

        // Sync saved menu immediately for stock tiles so gaps are compacted before HmgApi.syncSavedMenu runs
        if (ctx != null) {
            syncSavedMenu(mod, ctx, initialCfg, null);
        }

        // Enable post-hook listener for HmgApi.launcher (which runs next in LIFO afterHookedMethod order)
        // and schedule post-load finalisation once HmgApi.launcher + HmgApi.syncSavedMenu complete.
        postHooksActive.set(true);
        schedulePostLoadFinalize(mod, ctx);
    }

    private static void schedulePostLoadFinalize(final ModContext mod, final Context ctx) {
        if (ctx == null) return;
        final int seq = postLoadSeq.incrementAndGet();
        new Thread("hmgapi-tiles-postload") {
            @Override
            public void run() {
                try {
                    Thread.sleep(220);
                    if (seq != postLoadSeq.get()) return;
                    refreshCatalogAndSync(mod, ctx);
                } catch (Throwable t) {
                    mod.error("post-load tile finalize", t);
                }
            }
        }.start();
    }

    private static void refreshCatalogAndSync(ModContext mod, Context ctx) {
        if (ctx == null) return;
        List<Object> rawItems = snapshotLauncherRawItems();
        TileStore.MenuInventory inv = readMenuInventory(ctx);
        TileStore.Config cfg = effectiveConfig(mod, ctx, null);
        lastAppliedSignature = cfg.signature(mod.enabled());

        String catJson = buildCatalogJson(ctx, rawItems, inv, cfg);
        if (catJson != null) {
            lastCatalogJson = catJson;
            pushCatalogSync(ctx, catJson);
        }
        syncSavedMenu(mod, ctx, cfg, installedCatalogKeys(ctx));
    }

    private static List<Object> snapshotLauncherRawItems() {
        TileStore.FilterableMenuList list = liveLauncherList;
        Object lock = liveLauncherLock;
        if (list == null) return Collections.emptyList();
        if (lock != null) {
            synchronized (lock) {
                return list.snapshotRaw();
            }
        }
        return list.snapshotRaw();
    }

    private static String buildCatalogJson(Context ctx, List<Object> rawItems,
                                           TileStore.MenuInventory inv, TileStore.Config cfg) {
        Map<String, Map<String, Object>> catalogByKey = new LinkedHashMap<String, Map<String, Object>>();
        Set<String> coveredPackages = new LinkedHashSet<String>();
        Set<Integer> coveredItemIds = new LinkedHashSet<Integer>();
        Set<String> coveredSystemTitles = new LinkedHashSet<String>();
        Set<String> matchedKeys = new LinkedHashSet<String>();
        Set<String> rejectedKeys = new LinkedHashSet<String>();
        Set<String> rejectedSinglePkgs = new LinkedHashSet<String>();
        preSeedMatchedKeys(rawItems, matchedKeys);

        List<EntryMeta> extractedMetas = new ArrayList<EntryMeta>();
        List<Object> deferredItems = new ArrayList<Object>();
        Map<String, Set<String>> keysByPkg = new LinkedHashMap<String, Set<String>>();
        if (rawItems != null) {
            for (Object item : rawItems) {
                EntryMeta m = ensureMeta(ctx, item, launcherInspector, inv, matchedKeys, null);
                if (m == null || m.key.isEmpty()) {
                    deferredItems.add(item);
                    continue;
                }
                matchedKeys.add(m.key);
                extractedMetas.add(m);
                recordPkgKey(keysByPkg, m);
            }
            for (Object item : deferredItems) {
                EntryMeta m = ensureMeta(ctx, item, launcherInspector, inv, matchedKeys, null);
                if (m == null || m.key.isEmpty()) continue;
                matchedKeys.add(m.key);
                extractedMetas.add(m);
                recordPkgKey(keysByPkg, m);
            }
        }

        Set<String> multiEntryPackages = new LinkedHashSet<String>();
        multiEntryPackages.add("com.android.settings");
        for (Map.Entry<String, Set<String>> pe : keysByPkg.entrySet()) {
            if (pe.getValue().size() > 1) multiEntryPackages.add(pe.getKey());
        }

        TileStore.Gen5wFacts facts = getGen5wFacts(ctx);

        // 1. Entries from Launcher_WP3's menu list (stock 7-arg + overloaded ctors + HMG-API mods + NEMESIS).
        //    Note: never reject stock Q entries via isEnableMenu()/isAvailable()—greyed-out stock tiles
        //    (e.g. Phone Projection without phone connected, Valet Mode, Quiet Mode, HD Radio Data)
        //    remain on the Gen5W Home Screen and must stay visible & toggleable in Tile Manager.
        for (int i = 0; i < extractedMetas.size(); i++) {
            EntryMeta m = extractedMetas.get(i);
            if ("system".equals(m.kind)) {
                if (!isTargetComponentInstalled(ctx, m.pkg, m.cls)
                        || !TileStore.isSupportedStockFeature(m.title, m.pkg, m.cls, facts)
                        || !TileStore.isEquippedSystemTile(m.key, m.pkg, m.cls, inv, multiEntryPackages)) {
                    rejectedKeys.add(m.key);
                    if (!m.pkg.isEmpty() && !multiEntryPackages.contains(m.pkg)) {
                        rejectedSinglePkgs.add(m.pkg);
                    }
                    continue;
                }
            }
            putCatalogRow(catalogByKey, coveredPackages, m);
            if (m.itemId > 0) coveredItemIds.add(Integer.valueOf(m.itemId));
            if ("system".equals(m.kind) && !m.title.isEmpty()) {
                coveredSystemTitles.add(m.title.toLowerCase(Locale.US));
            }
        }

        // 2. HMG-API's own app + all enabled mods' provides.launcher entries (ensures 100% custom mod
        //    coverage even if Tile Manager is opened before Launcher_WP3's next load() cycle)
        if (isPackageInstalled(ctx, "hmgapi.app")) {
            EntryMeta hmgMeta = buildMeta(ctx, "hmgapi.app", "hmgapi.app.MainActivity", 0, "HMG-API", -1);
            if (!catalogByKey.containsKey(hmgMeta.key)) {
                putCatalogRow(catalogByKey, coveredPackages, hmgMeta);
            }
        }
        for (TileStore.ModLauncherSpec spec : TileStore.readHmgApiLauncherMods(PUBLIC_STATE, MODS_ROOT)) {
            if (!isPackageInstalled(ctx, spec.packageName)) continue;
            if (catalogByKey.containsKey(spec.key)) continue;
            EntryMeta m = buildMeta(ctx, spec.packageName, spec.className, 0, spec.title, -1);
            putCatalogRow(catalogByKey, coveredPackages, m);
        }

        // 3. Custom/mod entries in all_menu, backed-up hidden tiles, and stock bootstrap/fallback entries
        boolean hasLauncherItems = !extractedMetas.isEmpty();
        boolean hasUnextractedLauncherItems = rawItems != null && extractedMetas.size() < rawItems.size();
        if (inv != null) {
            for (TileStore.MenuEntryRecord rec : inv.activeEntries) {
                if (catalogByKey.containsKey(rec.key) || rejectedKeys.contains(rec.key)) continue;
                if (!rec.packageName.isEmpty() && rejectedSinglePkgs.contains(rec.packageName)) continue;
                if (!rec.packageName.isEmpty() && catalogByKey.containsKey(rec.packageName + "/*")) continue;
                if (!rec.packageName.isEmpty() && !multiEntryPackages.contains(rec.packageName)
                        && coveredPackages.contains(rec.packageName)) {
                    continue;
                }
                if (("*".equals(rec.className) || rec.className.isEmpty())
                        && !rec.packageName.isEmpty() && coveredPackages.contains(rec.packageName)) {
                    continue;
                }
                EntryMeta m = buildMeta(ctx, rec.packageName, rec.className, 0, null, rec.itemId);
                if (m.key.isEmpty() || catalogByKey.containsKey(m.key) || rejectedKeys.contains(m.key)) continue;
                if ("system".equals(m.kind)) {
                    if (rec.itemId > 0 && coveredItemIds.contains(Integer.valueOf(rec.itemId))) continue;
                    if (m.pkg.isEmpty() && !m.title.isEmpty()
                            && coveredSystemTitles.contains(m.title.toLowerCase(Locale.US))) {
                        continue;
                    }
                    boolean isBackedUp = inv.backedUpKeys.contains(rec.key)
                            || inv.backedUpKeys.contains(m.key)
                            || (!m.pkg.isEmpty() && inv.backedUpKeys.contains(m.pkg + "/*"));
                    if (hasLauncherItems && !hasUnextractedLauncherItems && !isBackedUp) continue;
                    if (!isTargetComponentInstalled(ctx, m.pkg, m.cls)
                            || !TileStore.isSupportedStockFeature(m.title, m.pkg, m.cls, facts)
                            || !TileStore.isEquippedSystemTile(m.key, m.pkg, m.cls, inv, multiEntryPackages)) {
                        continue;
                    }
                }
                putCatalogRow(catalogByKey, coveredPackages, m);
                if (rec.itemId > 0) coveredItemIds.add(Integer.valueOf(rec.itemId));
                if ("system".equals(m.kind) && !m.title.isEmpty()) {
                    coveredSystemTitles.add(m.title.toLowerCase(Locale.US));
                }
            }
        }

        if (catalogByKey.isEmpty()) return null;
        Map<String, Object> doc = new LinkedHashMap<String, Object>();
        doc.put("schema", Long.valueOf(TileStore.CATALOG_SCHEMA));
        doc.put("tiles", new ArrayList<Object>(catalogByKey.values()));
        return TileStore.writeCompact(doc);
    }

    private static void recordPkgKey(Map<String, Set<String>> keysByPkg, EntryMeta m) {
        if (m.pkg.isEmpty()) return;
        Set<String> ks = keysByPkg.get(m.pkg);
        if (ks == null) {
            ks = new LinkedHashSet<String>();
            keysByPkg.put(m.pkg, ks);
        }
        ks.add(m.key);
    }

    private static void putCatalogRow(Map<String, Map<String, Object>> catalogByKey,
                                      Set<String> coveredPackages, EntryMeta m) {
        if (catalogByKey.containsKey(m.key)) return;
        if (!m.pkg.isEmpty()) {
            String wildcard = m.pkg + "/*";
            if (!m.key.equals(wildcard) && catalogByKey.containsKey(wildcard)) {
                catalogByKey.remove(wildcard);
            } else if (m.key.equals(wildcard) && coveredPackages.contains(m.pkg)) {
                return;
            }
            coveredPackages.add(m.pkg);
        }
        Map<String, Object> row = new LinkedHashMap<String, Object>();
        row.put("key", m.key);
        row.put("packageName", m.pkg);
        row.put("className", m.cls);
        row.put("title", m.title);
        row.put("kind", m.kind);
        if (m.modId != null) row.put("mod", m.modId);
        catalogByKey.put(m.key, row);
    }

    private static boolean isPackageInstalled(Context ctx, String pkg) {
        if (ctx == null || pkg == null || pkg.isEmpty()) return false;
        try {
            ctx.getPackageManager().getApplicationInfo(pkg, 0);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean isTargetComponentInstalled(Context ctx, String pkg, String cls) {
        String p = TileStore.cleanToken(pkg);
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

            String fullCls = TileStore.expandClass(p, cls);
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

    private static TileStore.Gen5wFacts getGen5wFacts(Context ctx) {
        TileStore.Gen5wFacts existing = cachedFacts;
        if (existing != null) return existing;
        String product = readSysProp("ro.build.product");
        if (product.isEmpty()) product = readSysProp("ro.product.name");
        int regionCode = readBuildInfoRegion();
        Set<String> feats = TileStore.readPermissionXmlFeatures(PERMISSIONS_DIR);
        if (ctx != null) {
            try {
                FeatureInfo[] avail = ctx.getPackageManager().getSystemAvailableFeatures();
                if (avail != null) {
                    for (FeatureInfo fi : avail) {
                        if (fi != null && fi.name != null && !fi.name.trim().isEmpty()) {
                            feats.add(fi.name.trim().toLowerCase(Locale.US));
                        }
                    }
                }
            } catch (Throwable ignored) {}
        }
        Integer imsOpt = probeServiceOption("memoryseat", "seatmemory", "ims");
        Integer smlOpt = probeServiceOption("soundmood", "moodlamp", "sml");
        Integer dvrsOpt = probeServiceOption("dvrs", "builtincam");
        TileStore.Gen5wFacts built = new TileStore.Gen5wFacts(product, regionCode, feats, imsOpt, smlOpt, dvrsOpt);
        cachedFacts = built;
        return built;
    }

    private static String readSysProp(String key) {
        try {
            Class<?> sp = Class.forName("android.os.SystemProperties");
            Method m = sp.getMethod("get", String.class, String.class);
            Object v = m.invoke(null, key, "");
            return v != null ? TileStore.cleanToken(v) : "";
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

    private static void pushCatalogSync(Context ctx, String catJson) {
        if (ctx == null || catJson == null) return;
        try {
            ctx.getContentResolver().call(CATALOG_URI, "putCatalog", catJson, null);
        } catch (Throwable ignored) {
            // TileManager.apk not installed yet or starting up; ACTION_REQUEST_CATALOG retries on open
        }
    }

    private static void onEditorLoaded(final ModContext mod, final Context ctx, final ClassLoader cl,
                                       final Field itemsField, List<Object> list) {
        if (ctx != null) startEditorReceiver(mod, ctx);
        final TileStore.MenuInventory inv = readMenuInventory(ctx);
        final Set<String> matchedKeys = new LinkedHashSet<String>();
        synchronized (list) {
            preSeedMatchedKeys(list, matchedKeys);
        }

        TileStore.ItemVisibilityFilter filter = makeEditorFilter(mod, ctx, cl, inv, matchedKeys, null);
        synchronized (list) {
            editorLoader = cl;
            TileStore.FilterableMenuList managed;
            int hiddenCount;
            if (list instanceof TileStore.FilterableMenuList) {
                managed = (TileStore.FilterableMenuList) list;
                hiddenCount = managed.resetFrom(list, filter);
            } else {
                managed = new TileStore.FilterableMenuList(list, filter);
                hiddenCount = list.size() - managed.size();
                try {
                    itemsField.set(null, managed);
                } catch (Throwable t) {
                    mod.error("wrap homesetting.edit_list.items", t);
                }
            }
            liveEditorList = managed;
            if (hiddenCount > 0) {
                mod.log("editor: hid " + hiddenCount + " tile(s)");
            }
        }
    }

    private static TileStore.ItemVisibilityFilter makeEditorFilter(
            final ModContext mod, final Context ctx, final ClassLoader cl,
            final TileStore.MenuInventory inv, final Set<String> matchedKeys, final String overrideJson) {
        final TileStore.Config currentCfg = effectiveConfig(mod, ctx, overrideJson);
        return new TileStore.ItemVisibilityFilter() {
            @Override
            public void onItemAdded(Object item) {
                EntryMeta m = ensureMeta(ctx, item, editorInspector, inv, matchedKeys, cl);
                if (m != null && !m.key.isEmpty()) matchedKeys.add(m.key);
            }

            @Override
            public boolean shouldShow(Object item) {
                if (!mod.enabled()) return true;
                EntryMeta m = ensureMeta(ctx, item, editorInspector, inv, matchedKeys, cl);
                if (m == null || m.key.isEmpty()) return true;
                return TileStore.matchingHiddenKey(currentCfg.hidden, m.pkg, m.cls, m.itemId) == null;
            }
        };
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
                        new Thread("hmgapi-tiles-req") {
                            @Override
                            public void run() {
                                try {
                                    List<Object> raw = snapshotLauncherRawItems();
                                    TileStore.MenuInventory inv = readMenuInventory(appCtx);
                                    TileStore.Config cfg = effectiveConfig(mod, appCtx, null);
                                    String freshCat = buildCatalogJson(appCtx, raw, inv, cfg);
                                    if (freshCat != null) {
                                        lastCatalogJson = freshCat;
                                    }
                                    pushCatalogSync(appCtx, lastCatalogJson);
                                } catch (Throwable t) {
                                    mod.error("catalog request", t);
                                }
                            }
                        }.start();
                        return;
                    }
                    final String overrideJson = intent != null ? intent.getStringExtra("config_json") : null;
                    final TileStore.Config nextCfg = effectiveConfig(mod, appCtx, overrideJson);
                    final String nextSig = nextCfg.signature(mod.enabled());
                    if (ACTION_HMGAPI_STATE.equals(action) && nextSig.equals(lastAppliedSignature)) {
                        // A mod may have been installed/enabled/disabled: refresh catalog without killing launcher!
                        schedulePostLoadFinalize(mod, appCtx);
                        return;
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
                                List<Object> raw = snapshotLauncherRawItems();
                                TileStore.MenuInventory inv = readMenuInventory(appCtx);
                                String freshCat = buildCatalogJson(appCtx, raw, inv, fresh);
                                if (freshCat != null) {
                                    lastCatalogJson = freshCat;
                                    pushCatalogSync(appCtx, freshCat);
                                }
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
                    final TileStore.FilterableMenuList list = liveEditorList;
                    if (list == null) return;
                    final String overrideJson = intent != null ? intent.getStringExtra("config_json") : null;
                    new Thread("hmgapi-tiles-editor-sync") {
                        @Override
                        public void run() {
                            try {
                                TileStore.MenuInventory inv = readMenuInventory(appCtx);
                                Set<String> matched = new LinkedHashSet<String>();
                                TileStore.ItemVisibilityFilter filter = makeEditorFilter(
                                        mod, appCtx, editorLoader, inv, matched, overrideJson);
                                synchronized (list) {
                                    int hidden = list.refilter(filter);
                                    if (hidden > 0) mod.log("editor: live hid " + hidden + " tile(s)");
                                }
                            } catch (Throwable t) {
                                mod.error("editor live tile filter", t);
                            }
                        }
                    }.start();
                }
            }, f);
            editorReceiverStarted = true;
        }
    }

    private static TileStore.MenuInventory readMenuInventory(Context ctx) {
        if (ctx == null) {
            return TileStore.collectMenuInventory(Collections.<String>emptyList(), null);
        }
        Map<String, String> menusByUser = new LinkedHashMap<String, String>();
        Map<String, Object> backups = readBackupsMap(ctx);
        Cursor cur = null;
        try {
            cur = ctx.getContentResolver().query(ALL_MENU, null, null, null, null);
            if (cur != null) {
                int userCol = cur.getColumnIndex("userName");
                int itemsCol = cur.getColumnIndex("menu_items");
                int idx = 0;
                while (cur.moveToNext()) {
                    String user = userCol >= 0 ? TileStore.cleanToken(cur.getString(userCol)) : "";
                    String json = itemsCol >= 0 ? cur.getString(itemsCol) : null;
                    if (json == null || json.isEmpty()) continue;
                    String mapKey = (user.isEmpty() || menusByUser.containsKey(user))
                            ? ("#" + (idx++)) : user;
                    menusByUser.put(mapKey, json);
                }
            }
        } catch (Throwable ignored) {
        } finally {
            if (cur != null) cur.close();
        }
        return TileStore.collectMenuInventory(TileStore.selectProfileMenuJsons(menusByUser), backups);
    }

    private static Map<String, Object> readBackupsMap(Context ctx) {
        File bakFile = new File(ctx.getFilesDir(), TileStore.BACKUP_FILE);
        File legacyBak = new File(ctx.getFilesDir(), TileStore.LEGACY_BACKUP_FILE);
        File sourceBak = bakFile.isFile() ? bakFile : (legacyBak.isFile() ? legacyBak : null);
        if (sourceBak != null) {
            try {
                return TileStore.parseObject(TileStore.readUtf8(sourceBak));
            } catch (Throwable ignored) {}
        }
        return new LinkedHashMap<String, Object>();
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
                String key = TileStore.normKeyString(TileStore.str(row.get("key")));
                String pkg = TileStore.cleanToken(row.get("packageName"));
                String cls = TileStore.cleanToken(row.get("className"));
                String kind = TileStore.cleanToken(row.get("kind"));
                if (key.isEmpty()) key = TileStore.normKey(pkg, cls);
                if (key.isEmpty()) continue;

                // Built-in system tiles are part of the head unit ROM and should never have their
                // saved slot backups pruned as "uninstalled" (even if they use virtual class names).
                if (!"package".equals(kind)) {
                    out.add(key);
                    continue;
                }
                if (pm != null && !pkg.isEmpty()) {
                    try {
                        pm.getApplicationInfo(pkg, 0);
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
                File sourceBak = bakFile.isFile() ? bakFile : (legacyBak.isFile() ? legacyBak : null);
                Map<String, Object> backups = readBackupsMap(ctx);

                cur = cr.query(ALL_MENU, null, null, null, null);
                if (cur == null) return;
                int userCol = cur.getColumnIndex("userName");
                int itemsCol = cur.getColumnIndex("menu_items");
                boolean backupChanged = (sourceBak == legacyBak);
                while (cur.moveToNext()) {
                    String user = userCol >= 0 ? cur.getString(userCol) : null;
                    String json = itemsCol >= 0 ? cur.getString(itemsCol) : null;
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
