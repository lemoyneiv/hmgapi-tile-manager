package dev.lemoyne.tiles.hook;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Pure-JVM Home Screen tile visibility configuration, minimal JSON codec, universal menu-entry
 * reflection inspector, LIFO-safe filterable menu list, trim/region ghost filter, and saved-menu
 * grid synchroniser. Free of Android framework and internal HMG-API engine imports so
 * tools/unit/test.sh exercises every scenario directly on the host JVM.
 */
public final class TileStore {
    private TileStore() {}

    public static final int CATALOG_SCHEMA = 2;

    public static final String ACTION_TILES_CHANGED = "dev.lemoyne.tiles.ACTION_TILES_CHANGED";
    public static final String ACTION_REQUEST_CATALOG = "dev.lemoyne.tiles.ACTION_REQUEST_CATALOG";
    public static final String PROVIDER_AUTH = "dev.lemoyne.tiles.provider";

    public static final String BACKUP_FILE = "hmgapi-tile-manager-backup.json";
    public static final String LEGACY_BACKUP_FILE = "hmgapi-hidden-backup.json";
    public static final String LEGACY_PUBLIC_CONFIG = "/data/hmgapi/public/launcher-tiles.json";

    public static final class Config {
        public final boolean exists;
        public final boolean compactGaps;
        public final Set<String> hidden;

        public Config(boolean exists, boolean compactGaps, Set<String> hidden) {
            this.exists = exists;
            this.compactGaps = compactGaps;
            this.hidden = Collections.unmodifiableSet(
                    hidden == null ? Collections.<String>emptySet() : new LinkedHashSet<String>(hidden));
        }

        public String signature(boolean modEnabled) {
            StringBuilder b = new StringBuilder();
            b.append(modEnabled ? '1' : '0').append('|')
             .append(exists ? '1' : '0').append('|')
             .append(compactGaps ? '1' : '0').append('|');
            List<String> sorted = new ArrayList<String>(hidden);
            Collections.sort(sorted);
            for (int i = 0; i < sorted.size(); i++) {
                if (i > 0) b.append(',');
                b.append(sorted.get(i));
            }
            return b.toString();
        }
    }

    public static final class SyncResult {
        public final String updatedMenuJson;
        public final boolean backupChanged;
        public final List<String> logs;

        SyncResult(String updatedMenuJson, boolean backupChanged, List<String> logs) {
            this.updatedMenuJson = updatedMenuJson;
            this.backupChanged = backupChanged;
            this.logs = logs;
        }
    }

    public static final class MenuEntryRecord {
        public final String key;
        public final String packageName;
        public final String className;
        public final int itemId;

        public MenuEntryRecord(String key, String packageName, String className, int itemId) {
            this.key = key;
            this.packageName = packageName;
            this.className = className;
            this.itemId = itemId;
        }
    }

    public static final class MenuInventory {
        public final List<MenuEntryRecord> activeEntries;
        public final Set<String> equippedKeys;
        public final Set<String> equippedPackages;
        public final Map<String, String> classToPackage;
        public final Map<Integer, MenuEntryRecord> byItemId;

        public MenuInventory(List<MenuEntryRecord> activeEntries, Set<String> equippedKeys,
                             Set<String> equippedPackages, Map<String, String> classToPackage,
                             Map<Integer, MenuEntryRecord> byItemId) {
            this.activeEntries = Collections.unmodifiableList(activeEntries);
            this.equippedKeys = Collections.unmodifiableSet(equippedKeys);
            this.equippedPackages = Collections.unmodifiableSet(equippedPackages);
            this.classToPackage = Collections.unmodifiableMap(classToPackage);
            this.byItemId = Collections.unmodifiableMap(byItemId);
        }

        public boolean hasAnyData() {
            return !equippedKeys.isEmpty() || !activeEntries.isEmpty();
        }
    }

    public static final class ModLauncherSpec {
        public final String modId;
        public final String packageName;
        public final String className;
        public final String title;
        public final String key;

        public ModLauncherSpec(String modId, String packageName, String className, String title) {
            this.modId = modId;
            this.packageName = cleanToken(packageName);
            this.className = expandClass(this.packageName, className);
            this.title = title == null ? "" : title.trim();
            this.key = normKey(this.packageName, this.className);
        }
    }

    public interface IntStringProbe {
        String resolveStringResource(int resId);
    }

    public interface ItemVisibilityFilter {
        boolean shouldShow(Object item);
        void onItemAdded(Object item);
    }

    /**
     * ArrayList wrapper installed on launcher.menu_list.items and homesetting.edit_list.items
     * before HMG-API's own afterHookedMethod runs (Bridge.handleHookedMethod executes after-hooks
     * in LIFO order). Any mod/custom tile appended later by HmgApi.launcher, HmgApi.editor, or
     * NEMESIS is automatically recorded in rawItems and filtered from the visible list if hidden.
     */
    public static final class FilterableMenuList extends ArrayList<Object> {
        private static final long serialVersionUID = 1L;
        private final List<Object> rawItems = new ArrayList<Object>();
        private transient ItemVisibilityFilter filter;
        private transient boolean internalRefiltering;

        public FilterableMenuList(Collection<?> initialItems, ItemVisibilityFilter filter) {
            super();
            this.filter = filter;
            resetFrom(initialItems, filter);
        }

        public synchronized void setFilter(ItemVisibilityFilter filter) {
            this.filter = filter;
        }

        public synchronized int resetFrom(Collection<?> source, ItemVisibilityFilter nextFilter) {
            if (nextFilter != null) this.filter = nextFilter;
            if (source == this) {
                return refilter(this.filter);
            }
            List<Object> copy = source == null ? Collections.emptyList() : new ArrayList<Object>(source);
            rawItems.clear();
            rawItems.addAll(copy);
            return rebuildVisibleLocked();
        }

        public synchronized int refilter(ItemVisibilityFilter nextFilter) {
            if (nextFilter != null) this.filter = nextFilter;
            return rebuildVisibleLocked();
        }

        private int rebuildVisibleLocked() {
            internalRefiltering = true;
            int hiddenCount = 0;
            try {
                super.clear();
                for (Object item : rawItems) {
                    if (filter != null) filter.onItemAdded(item);
                    if (filter == null || filter.shouldShow(item)) {
                        super.add(item);
                    } else {
                        hiddenCount++;
                    }
                }
            } finally {
                internalRefiltering = false;
            }
            return hiddenCount;
        }

        public synchronized List<Object> snapshotRaw() {
            return new ArrayList<Object>(rawItems);
        }

        @Override
        public synchronized boolean add(Object e) {
            if (internalRefiltering) return super.add(e);
            rawItems.add(e);
            if (filter != null) filter.onItemAdded(e);
            if (filter != null && !filter.shouldShow(e)) {
                return false;
            }
            return super.add(e);
        }

        @Override
        public synchronized void add(int index, Object element) {
            if (internalRefiltering) {
                super.add(index, element);
                return;
            }
            rawItems.add(element);
            if (filter != null) filter.onItemAdded(element);
            if (filter != null && !filter.shouldShow(element)) {
                return;
            }
            int safeIndex = Math.max(0, Math.min(index, super.size()));
            super.add(safeIndex, element);
        }

        @Override
        public synchronized boolean addAll(Collection<?> c) {
            if (c == null || c.isEmpty()) return false;
            if (internalRefiltering) return super.addAll(c);
            boolean modified = false;
            for (Object e : c) {
                if (add(e)) modified = true;
            }
            return modified;
        }

        @Override
        public synchronized boolean addAll(int index, Collection<?> c) {
            if (c == null || c.isEmpty()) return false;
            if (internalRefiltering) return super.addAll(index, c);
            int cur = Math.max(0, Math.min(index, super.size()));
            boolean modified = false;
            for (Object e : c) {
                rawItems.add(e);
                if (filter != null) filter.onItemAdded(e);
                if (filter == null || filter.shouldShow(e)) {
                    super.add(cur++, e);
                    modified = true;
                }
            }
            return modified;
        }

        @Override
        public synchronized void clear() {
            if (!internalRefiltering) {
                rawItems.clear();
            }
            super.clear();
        }
    }

    /**
     * Runtime reflection inspector for Launcher_WP3 (com.android.launcher3.view.a.Q) and
     * HomeSetting (com.hkmc.system.app.homesetting.allmenuedit.c) entries. Calibrates exact
     * obfuscated fields from observed 7-argument constructor calls so entries built via 6-arg,
     * 8-arg, ComponentName, Intent, or custom-label constructors (e.g. EV, Navigation Menu,
     * Hyundai Pay, Radio, Bluelink, Notifications, User's Manual, and NEMESIS apps) are
     * extracted with full fidelity.
     */
    public static final class EntryInspector {
        public static final class Extracted {
            public final String pkg;
            public final String cls;
            public final int titleId;
            public final String titleText;
            public final int matchedItemId;

            public Extracted(String pkg, String cls, int titleId, String titleText, int matchedItemId) {
                this.pkg = cleanToken(pkg);
                this.cls = cleanToken(cls);
                this.titleId = titleId;
                this.titleText = titleText == null ? "" : titleText.trim();
                this.matchedItemId = matchedItemId;
            }
        }

        private Set<Field> pkgCandidates;
        private Set<Field> clsCandidates;
        private Set<Field> compCandidates;
        private Set<Field> titleIdCandidates;
        private Set<Field> titleTextCandidates;

        public synchronized void calibrate(Object instance, String pkg, String cls,
                                           int iconId, int iconDisabledId, int titleId, String resolvedTitle) {
            if (instance == null) return;
            String p = cleanToken(pkg);
            String c = cleanToken(cls);
            String fullCls = expandClass(p, c);
            List<Field> fields = allInstanceFields(instance.getClass());

            if (!p.isEmpty() && !p.equals(c) && !p.equals(fullCls)) {
                Set<Field> foundPkg = new LinkedHashSet<Field>();
                Set<Field> foundComp = new LinkedHashSet<Field>();
                for (Field f : fields) {
                    Object val = readField(f, instance);
                    if (val instanceof String) {
                        String s = ((String) val).trim();
                        if (p.equals(s)) foundPkg.add(f);
                    } else if (val != null && "android.content.ComponentName".equals(val.getClass().getName())) {
                        String cp = cleanToken(callZeroArgString(val, "getPackageName"));
                        if (p.equals(cp)) foundComp.add(f);
                    }
                }
                if (!foundPkg.isEmpty()) pkgCandidates = narrow(pkgCandidates, foundPkg);
                if (!foundComp.isEmpty()) compCandidates = narrow(compCandidates, foundComp);
            }

            if (!c.isEmpty() && !"*".equals(c) && !p.equals(c)) {
                Set<Field> foundCls = new LinkedHashSet<Field>();
                for (Field f : fields) {
                    Object val = readField(f, instance);
                    if (val instanceof String) {
                        String s = ((String) val).trim();
                        if (c.equals(s) || fullCls.equals(s)) foundCls.add(f);
                    }
                }
                if (!foundCls.isEmpty()) clsCandidates = narrow(clsCandidates, foundCls);
            }

            if (titleId != 0 && titleId != iconId && titleId != iconDisabledId) {
                Set<Field> foundTid = new LinkedHashSet<Field>();
                for (Field f : fields) {
                    Class<?> t = f.getType();
                    if (t == int.class || t == Integer.class) {
                        Object val = readField(f, instance);
                        if (val instanceof Number && ((Number) val).intValue() == titleId) {
                            foundTid.add(f);
                        }
                    }
                }
                if (!foundTid.isEmpty()) titleIdCandidates = narrow(titleIdCandidates, foundTid);
            }

            String rt = resolvedTitle == null ? "" : resolvedTitle.trim();
            if (!rt.isEmpty() && !rt.equals(p) && !rt.equals(c) && !rt.equals(fullCls)) {
                Set<Field> foundTxt = new LinkedHashSet<Field>();
                for (Field f : fields) {
                    if (CharSequence.class.isAssignableFrom(f.getType())) {
                        Object val = readField(f, instance);
                        if (val != null && rt.equals(val.toString().trim())) {
                            foundTxt.add(f);
                        }
                    }
                }
                if (!foundTxt.isEmpty()) titleTextCandidates = narrow(titleTextCandidates, foundTxt);
            }
        }

        private static Set<Field> narrow(Set<Field> existing, Set<Field> fresh) {
            if (existing == null || existing.isEmpty()) return fresh;
            Set<Field> inter = new LinkedHashSet<Field>(existing);
            inter.retainAll(fresh);
            return inter.isEmpty() ? existing : inter;
        }

        private static Field firstApplicable(Set<Field> set, Object target) {
            if (set == null) return null;
            for (Field f : set) {
                if (f.getDeclaringClass().isInstance(target)) return f;
            }
            return null;
        }

        public synchronized Extracted extract(Object item, MenuInventory inv,
                                              Set<String> alreadyMatchedKeys, IntStringProbe stringProbe) {
            if (item == null) return null;
            String pkg = "";
            String cls = "";
            int titleId = 0;
            String titleText = "";
            int matchedItemId = -1;

            Field pf = firstApplicable(pkgCandidates, item);
            Field cf = firstApplicable(clsCandidates, item);
            Field compF = firstApplicable(compCandidates, item);
            Field tidF = firstApplicable(titleIdCandidates, item);
            Field txtF = firstApplicable(titleTextCandidates, item);

            if (pf != null) pkg = cleanToken(readField(pf, item));
            if (cf != null) cls = cleanToken(readField(cf, item));
            if (compF != null && (pkg.isEmpty() || cls.isEmpty())) {
                Object comp = readField(compF, item);
                if (comp != null) {
                    if (pkg.isEmpty()) pkg = cleanToken(callZeroArgString(comp, "getPackageName"));
                    if (cls.isEmpty()) cls = cleanToken(callZeroArgString(comp, "getClassName"));
                }
            }
            if (tidF != null) {
                Object v = readField(tidF, item);
                if (v instanceof Number) titleId = ((Number) v).intValue();
            }
            if (txtF != null) {
                Object v = readField(txtF, item);
                if (v != null) titleText = v.toString().trim();
            }

            // 2. Standard getter methods on entry / superclasses
            if (pkg.isEmpty()) {
                pkg = firstNonEmptyGetter(item, "getPackageName", "getPkgName", "getPackage");
            }
            if (cls.isEmpty()) {
                cls = firstNonEmptyGetter(item, "getClassName", "getActivityName", "getClsName");
            }
            if (pkg.isEmpty() || cls.isEmpty()) {
                Object comp = firstObjectGetter(item, "getComponentName", "getComponent");
                if (comp != null) {
                    if (pkg.isEmpty()) pkg = cleanToken(callZeroArgString(comp, "getPackageName"));
                    if (cls.isEmpty()) cls = cleanToken(callZeroArgString(comp, "getClassName"));
                }
            }
            if (titleId == 0) {
                Integer tid = firstIntGetter(item, "getTitleId", "getTitleResId", "getStringId");
                if (tid != null) titleId = tid.intValue();
            }
            if (titleText.isEmpty()) {
                titleText = firstNonEmptyGetter(item, "getTitle", "getLabel", "getAppName");
            }

            // 3. Field scan for ComponentName, Intent, String pkg/cls/title, and string resource IDs
            List<Field> allFields = allInstanceFields(item.getClass());
            if (pkg.isEmpty() || cls.isEmpty()) {
                for (Field f : allFields) {
                    Object val = readField(f, item);
                    if (val == null) continue;
                    String typeName = val.getClass().getName();
                    if ("android.content.ComponentName".equals(typeName)) {
                        if (pkg.isEmpty()) pkg = cleanToken(callZeroArgString(val, "getPackageName"));
                        if (cls.isEmpty()) cls = cleanToken(callZeroArgString(val, "getClassName"));
                    } else if ("android.content.Intent".equals(typeName)) {
                        Object comp = callZeroArgObject(val, "getComponent");
                        if (comp != null) {
                            if (pkg.isEmpty()) pkg = cleanToken(callZeroArgString(comp, "getPackageName"));
                            if (cls.isEmpty()) cls = cleanToken(callZeroArgString(comp, "getClassName"));
                        }
                        if (pkg.isEmpty()) pkg = cleanToken(callZeroArgString(val, "getPackage"));
                    }
                }
            }

            List<String> extraStrings = new ArrayList<String>();
            for (Field f : allFields) {
                if (f.equals(pf) || f.equals(cf)) continue;
                Object val = readField(f, item);
                if (val instanceof CharSequence) {
                    String s = val.toString().trim();
                    if (!s.isEmpty() && !"null".equalsIgnoreCase(s)) extraStrings.add(s);
                }
            }

            if (pkg.isEmpty() && !extraStrings.isEmpty()) {
                // First match against known all_menu packages/classes
                if (inv != null) {
                    for (String s : extraStrings) {
                        if (inv.equippedPackages.contains(s)) {
                            pkg = s;
                            break;
                        }
                        String mappedPkg = inv.classToPackage.get(s);
                        if (mappedPkg != null && !mappedPkg.isEmpty()) {
                            pkg = mappedPkg;
                            if (cls.isEmpty()) cls = s;
                            break;
                        }
                        if (cls.isEmpty() && inv.equippedKeys.contains("@class/" + s)) {
                            cls = s;
                        }
                    }
                }
                if (pkg.isEmpty() && cls.isEmpty()) {
                    for (String s : extraStrings) {
                        if (looksLikePackageName(s)) {
                            pkg = s;
                            break;
                        }
                    }
                }
                if (!pkg.isEmpty() && cls.isEmpty()) {
                    for (String s : extraStrings) {
                        if (s.equals(pkg)) continue;
                        if (s.startsWith(".") || s.startsWith(pkg + ".") || looksLikeClassName(s)) {
                            cls = s;
                            break;
                        }
                    }
                } else if (pkg.isEmpty() && cls.isEmpty()) {
                    for (String s : extraStrings) {
                        if (looksLikeClassName(s)) {
                            cls = s;
                            break;
                        }
                    }
                }
            } else if (!pkg.isEmpty() && cls.isEmpty() && inv != null) {
                for (String s : extraStrings) {
                    if (s.equals(pkg)) continue;
                    String full = expandClass(pkg, s);
                    if (inv.equippedKeys.contains(pkg + "/" + full)) {
                        cls = s;
                        break;
                    }
                }
            }

            // Probe int fields for R.string.* / HMG-API 0x7f7e0000 title resource IDs
            if (titleId == 0 && titleText.isEmpty() && stringProbe != null) {
                for (Field f : allFields) {
                    Class<?> t = f.getType();
                    if (t != int.class && t != Integer.class) continue;
                    Object val = readField(f, item);
                    if (!(val instanceof Number)) continue;
                    int id = ((Number) val).intValue();
                    if ((id & 0xff000000) != 0x7f000000) continue;
                    String resolved = stringProbe.resolveStringResource(id);
                    if (resolved != null && !resolved.trim().isEmpty()) {
                        titleId = id;
                        titleText = resolved.trim();
                        break;
                    }
                }
            }

            String fullCls = expandClass(pkg, cls);
            if (titleText.isEmpty()) {
                for (String s : extraStrings) {
                    if (s.equals(pkg) || s.equals(cls) || s.equals(fullCls)) continue;
                    if (looksLikePackageName(s) || looksLikeClassName(s)) continue;
                    titleText = s;
                    break;
                }
            }

            // 4. Fallback itemId correlation when an entry has no package/class strings on Q
            if (pkg.isEmpty() && cls.isEmpty() && inv != null && !inv.byItemId.isEmpty()) {
                MenuEntryRecord uniqueMatch = null;
                int matches = 0;
                for (Field f : allFields) {
                    Class<?> t = f.getType();
                    if (t != int.class && t != Integer.class) continue;
                    Object val = readField(f, item);
                    if (!(val instanceof Number)) continue;
                    int n = ((Number) val).intValue();
                    if (n <= 0 || n == 39 || n >= 9000) continue;
                    MenuEntryRecord rec = inv.byItemId.get(Integer.valueOf(n));
                    if (rec != null && (alreadyMatchedKeys == null || !alreadyMatchedKeys.contains(rec.key))) {
                        uniqueMatch = rec;
                        matches++;
                    }
                }
                if (matches == 1 && uniqueMatch != null) {
                    pkg = uniqueMatch.packageName;
                    cls = uniqueMatch.className;
                    matchedItemId = uniqueMatch.itemId;
                }
            }

            if (pkg.isEmpty() && cls.isEmpty() && matchedItemId <= 0) return null;
            return new Extracted(pkg, cls, titleId, titleText, matchedItemId);
        }

        private static List<Field> allInstanceFields(Class<?> c) {
            List<Field> out = new ArrayList<Field>();
            for (Class<?> cur = c; cur != null && cur != Object.class; cur = cur.getSuperclass()) {
                Field[] declared = cur.getDeclaredFields();
                if (declared == null) continue;
                for (Field f : declared) {
                    if (Modifier.isStatic(f.getModifiers())) continue;
                    try {
                        f.setAccessible(true);
                        out.add(f);
                    } catch (Throwable ignored) {}
                }
            }
            return out;
        }

        private static Object readField(Field f, Object target) {
            try {
                return f.get(target);
            } catch (Throwable ignored) {
                return null;
            }
        }

        private static Object callZeroArgObject(Object target, String methodName) {
            if (target == null) return null;
            for (Class<?> cur = target.getClass(); cur != null && cur != Object.class; cur = cur.getSuperclass()) {
                try {
                    Method m = cur.getDeclaredMethod(methodName);
                    if (Modifier.isStatic(m.getModifiers())) continue;
                    m.setAccessible(true);
                    return m.invoke(target);
                } catch (NoSuchMethodException ignored) {
                } catch (Throwable ignored) {
                    return null;
                }
            }
            return null;
        }

        private static String callZeroArgString(Object target, String methodName) {
            Object v = callZeroArgObject(target, methodName);
            return v instanceof CharSequence ? v.toString().trim() : "";
        }

        private static String firstNonEmptyGetter(Object target, String... names) {
            for (String n : names) {
                String s = cleanToken(callZeroArgString(target, n));
                if (!s.isEmpty()) return s;
            }
            return "";
        }

        private static Object firstObjectGetter(Object target, String... names) {
            for (String n : names) {
                Object o = callZeroArgObject(target, n);
                if (o != null) return o;
            }
            return null;
        }

        private static Integer firstIntGetter(Object target, String... names) {
            for (String n : names) {
                Object o = callZeroArgObject(target, n);
                if (o instanceof Number) {
                    int v = ((Number) o).intValue();
                    if (v != 0) return Integer.valueOf(v);
                }
            }
            return null;
        }
    }

    static String cleanToken(Object o) {
        if (o == null) return "";
        String s = String.valueOf(o).trim();
        return "null".equalsIgnoreCase(s) ? "" : s;
    }

    public static String expandClass(String pkg, String cls) {
        String p = cleanToken(pkg);
        String c = cleanToken(cls);
        if (!p.isEmpty() && c.startsWith(".")) return p + c;
        return c;
    }

    static boolean looksLikePackageName(String s) {
        if (s == null || s.indexOf('.') <= 0 || s.indexOf(' ') >= 0 || s.indexOf('/') >= 0) return false;
        String[] parts = s.split("\\.");
        if (parts.length < 2) return false;
        for (String part : parts) {
            if (part.isEmpty()) return false;
            char first = part.charAt(0);
            if (!((first >= 'a' && first <= 'z') || (first >= 'A' && first <= 'Z'))) return false;
            for (int i = 0; i < part.length(); i++) {
                char ch = part.charAt(i);
                if (!((ch >= 'a' && ch <= 'z') || (ch >= '0' && ch <= '9') || ch == '_'
                        || (ch >= 'A' && ch <= 'Z'))) return false;
            }
        }
        // Package names start with a lowercase top-level domain (com.*, dev.*, org.*, kr.*, etc.)
        char c0 = parts[0].charAt(0);
        if (!(c0 >= 'a' && c0 <= 'z')) return false;
        // Last segment of a pure package name starts lowercase (unlike com.foo.MainActivity)
        char last0 = parts[parts.length - 1].charAt(0);
        return last0 >= 'a' && last0 <= 'z';
    }

    static boolean looksLikeClassName(String s) {
        if (s == null || s.indexOf(' ') >= 0 || s.indexOf('/') >= 0) return false;
        if (s.startsWith(".") && s.length() > 1) {
            char c1 = s.charAt(1);
            return c1 >= 'A' && c1 <= 'Z';
        }
        int dot = s.lastIndexOf('.');
        if (dot <= 0 || dot + 1 >= s.length()) return false;
        char cl = s.charAt(dot + 1);
        return cl >= 'A' && cl <= 'Z';
    }

    public static boolean isStockOemPackage(String pkg) {
        String p = cleanToken(pkg);
        if (p.isEmpty()) return true;
        String low = p.toLowerCase(Locale.US);
        if (low.contains("nemesis") || low.contains("xwtk") || low.contains("hmgapi")
                || low.contains("leafia") || low.contains("evergreen") || low.startsWith("dev.lemoyne.")) {
            return false;
        }
        return low.startsWith("com.hkmc.")
                || low.startsWith("com.hyundai.")
                || low.startsWith("com.kia.")
                || low.startsWith("com.genesis.")
                || low.startsWith("com.mnsoft.")
                || low.startsWith("com.daudio.")
                || low.startsWith("com.mobis.")
                || low.startsWith("com.bul.")
                || low.startsWith("com.lge.")
                || low.startsWith("com.obigo.")
                || low.startsWith("com.telechips.")
                || low.startsWith("com.sdcard.")
                || low.startsWith("com.android.")
                || low.startsWith("com.google.android.projection.")
                || low.startsWith("com.siriusxm.")
                || low.startsWith("com.aharadio.")
                || low.startsWith("com.soundhound.")
                || low.startsWith("com.melodis.")
                || low.startsWith("com.harman.")
                || low.startsWith("com.qnx.")
                || low.startsWith("com.nuance.")
                || low.startsWith("com.naver.")
                || low.startsWith("com.melon.")
                || low.startsWith("com.genie.")
                || low.startsWith("com.kakao.")
                || "android".equals(low);
    }

    public static String friendlySystemTitle(String rawLabel, String pkg, String cls) {
        String label = rawLabel == null ? "" : rawLabel.replace('\n', ' ').replace('\r', ' ').replaceAll(" +", " ").trim();
        String p = cleanToken(pkg);
        String c = cleanToken(cls);
        boolean looksTechnical = label.isEmpty()
                || label.startsWith("HKMC_")
                || label.endsWith("_WP")
                || label.endsWith("_WP3")
                || label.equalsIgnoreCase("QRManual")
                || label.equalsIgnoreCase("AppManual")
                || (label.indexOf('.') > 0 && label.indexOf(' ') < 0)
                || label.equals(p);
        if (!looksTechnical) return label;

        String probe = (p + "/" + c + " " + label).toLowerCase(Locale.US);
        if (probe.contains("qrmanual") || probe.contains("webmanual") || probe.contains("usermanual")
                || probe.contains("appmanual")) {
            return "User's Manual";
        }
        if (probe.contains("carpay") || probe.contains("hyundaipay") || probe.contains("invehiclepay")) {
            return "Hyundai Pay";
        }
        if (probe.contains("bluelink") || probe.contains("telematics.tms") || probe.contains("hkmc.telematics")) {
            return "Bluelink";
        }
        if (probe.contains("notification") || probe.contains("noticecenter") || probe.contains("noticelist")) {
            return "Notifications";
        }
        if (probe.contains("mnsoft") || probe.contains("navimenu") || probe.contains(".navi")) {
            return "Navigation Menu";
        }
        if (probe.contains("com.hkmc.ev") || probe.contains(".evactivity") || probe.contains(".evmain")) {
            return "EV";
        }
        if (probe.contains("radio")) {
            return "Radio";
        }
        if (!label.isEmpty() && !label.equals(p)) {
            String cleaned = label.replaceFirst("^(?i)HKMC_", "").replaceFirst("(?i)_WP3?$", "").replace('_', ' ').trim();
            if (!cleaned.isEmpty()) return cleaned;
        }
        if (!c.isEmpty() && !"*".equals(c)) {
            int dot = c.lastIndexOf('.');
            String simple = dot >= 0 && dot + 1 < c.length() ? c.substring(dot + 1) : c;
            simple = simple.replaceFirst("Activity$", "");
            if (!simple.isEmpty()) return simple;
        }
        return !p.isEmpty() ? p : "Home Tile";
    }

    @SuppressWarnings("unchecked")
    public static List<ModLauncherSpec> readHmgApiLauncherMods(File publicStateFile, File modsRootDir) {
        List<ModLauncherSpec> out = new ArrayList<ModLauncherSpec>();
        if (publicStateFile == null || !publicStateFile.isFile()) return out;
        try {
            Map<String, Object> root = parseObject(readUtf8(publicStateFile));
            Object modsObj = root.get("mods");
            if (!(modsObj instanceof Map)) return out;
            for (Map.Entry<String, Object> e : ((Map<String, Object>) modsObj).entrySet()) {
                String modId = e.getKey();
                if (!(e.getValue() instanceof Map)) continue;
                Map<String, Object> mState = (Map<String, Object>) e.getValue();
                if (!Boolean.TRUE.equals(mState.get("enabled"))) continue;
                if (modsRootDir == null) continue;
                File modJson = new File(new File(modsRootDir, modId), "mod.json");
                if (!modJson.isFile() || !modJson.canRead()) continue;
                try {
                    Map<String, Object> mj = parseObject(readUtf8(modJson));
                    Object prov = mj.get("provides");
                    if (!(prov instanceof Map)) continue;
                    Object launcher = ((Map<String, Object>) prov).get("launcher");
                    if (!(launcher instanceof List)) continue;
                    for (Object item : (List<Object>) launcher) {
                        if (!(item instanceof Map)) continue;
                        Map<String, Object> lm = (Map<String, Object>) item;
                        String pkg = cleanToken(lm.get("package"));
                        String cls = cleanToken(lm.get("class"));
                        String title = cleanToken(lm.get("title"));
                        if (pkg.isEmpty()) continue;
                        ModLauncherSpec spec = new ModLauncherSpec(modId, pkg, cls, title);
                        if (!spec.key.isEmpty()) out.add(spec);
                    }
                } catch (Throwable ignored) {}
            }
        } catch (Throwable ignored) {}
        return out;
    }

    public static boolean isEmptySlot(String pkg, String cls) {
        return isEmptySlot(pkg, cls, -1);
    }

    public static boolean isEmptySlot(String pkg, String cls, int itemId) {
        String p = cleanToken(pkg);
        String c = cleanToken(cls);
        if ("Empty view".equals(p) || "Empty view".equals(c)) return true;
        return p.isEmpty() && c.isEmpty() && (itemId <= 0 || itemId == 39);
    }

    public static String normKey(String pkg, String cls) {
        return normKey(pkg, cls, -1);
    }

    public static String normKey(String pkg, String cls, int itemId) {
        String p = cleanToken(pkg);
        String c = cleanToken(cls);
        if ("Empty view".equals(p) || "Empty view".equals(c)) return "";
        if (!p.isEmpty()) {
            if (c.isEmpty() || "*".equals(c)) return p + "/*";
            if (c.startsWith(".")) c = p + c;
            return p + "/" + c;
        }
        if (!c.isEmpty() && !"*".equals(c)) {
            return "@class/" + c;
        }
        if (itemId > 0 && itemId != 39) {
            return "@item/" + itemId;
        }
        return "";
    }

    public static String normKeyString(String raw) {
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
            int id = intVal(right, -1);
            return (id > 0 && id != 39) ? "@item/" + id : "";
        }
        return normKey(left, right, -1);
    }

    /**
     * Returns the matching key from hidden (handling exact pkg/cls, package-only pkg/* wildcards,
     * and all_menu entries with empty className), or null if the entry is visible.
     */
    public static String matchingHiddenKey(Set<String> hidden, String pkg, String cls, int itemId) {
        if (hidden == null || hidden.isEmpty()) return null;
        String exact = normKey(pkg, cls, itemId);
        if (exact.isEmpty()) return null;
        if (hidden.contains(exact)) return exact;

        String p = cleanToken(pkg);
        if (!p.isEmpty()) {
            String wildcard = p + "/*";
            if (hidden.contains(wildcard)) return wildcard;
            String c = cleanToken(cls);
            if (c.isEmpty() || "*".equals(c)) {
                String prefix = p + "/";
                String matched = null;
                int count = 0;
                for (String hk : hidden) {
                    if (hk.startsWith(prefix)) {
                        matched = hk;
                        count++;
                    }
                }
                if (count == 1) return matched;
            }
        }
        if (itemId > 0 && itemId != 39) {
            String itemKey = "@item/" + itemId;
            if (hidden.contains(itemKey)) return itemKey;
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    public static MenuInventory collectMenuInventory(List<String> profileMenuJsons, Map<String, Object> allBackups) {
        Map<String, MenuEntryRecord> byKey = new LinkedHashMap<String, MenuEntryRecord>();
        Set<String> equippedKeys = new LinkedHashSet<String>();
        Set<String> equippedPackages = new LinkedHashSet<String>();
        Map<String, String> classToPackage = new LinkedHashMap<String, String>();
        Map<Integer, MenuEntryRecord> byItemId = new LinkedHashMap<Integer, MenuEntryRecord>();

        if (profileMenuJsons != null) {
            for (String menuJson : profileMenuJsons) {
                if (menuJson == null || menuJson.trim().isEmpty()) continue;
                try {
                    Map<String, Object> root = parseObject(menuJson);
                    Object rawArr = root.get("menuItems");
                    if (!(rawArr instanceof List)) continue;
                    for (Object o : (List<Object>) rawArr) {
                        if (!(o instanceof Map)) continue;
                        recordInventoryItem((Map<String, Object>) o, byKey, equippedKeys,
                                equippedPackages, classToPackage, byItemId);
                    }
                } catch (Throwable ignored) {}
            }
        }

        if (allBackups != null) {
            Object profObj = allBackups.get("profiles");
            if (profObj instanceof Map) {
                for (Object userMap : ((Map<String, Object>) profObj).values()) {
                    if (!(userMap instanceof Map)) continue;
                    for (Map.Entry<String, Object> be : ((Map<String, Object>) userMap).entrySet()) {
                        String bk = normKeyString(be.getKey());
                        if (!bk.isEmpty()) equippedKeys.add(bk);
                        if (be.getValue() instanceof Map) {
                            recordInventoryItem((Map<String, Object>) be.getValue(), byKey, equippedKeys,
                                    equippedPackages, classToPackage, byItemId);
                        }
                    }
                }
            }
        }

        return new MenuInventory(new ArrayList<MenuEntryRecord>(byKey.values()),
                equippedKeys, equippedPackages, classToPackage, byItemId);
    }

    private static void recordInventoryItem(Map<String, Object> it,
                                            Map<String, MenuEntryRecord> byKey,
                                            Set<String> equippedKeys,
                                            Set<String> equippedPackages,
                                            Map<String, String> classToPackage,
                                            Map<Integer, MenuEntryRecord> byItemId) {
        String pkg = cleanToken(it.get("packageName"));
        String cls = cleanToken(it.get("className"));
        int id = intVal(it.get("itemId"), -1);
        if (isEmptySlot(pkg, cls, id)) return;
        String key = normKey(pkg, cls, id);
        if (key.isEmpty()) return;

        MenuEntryRecord rec = new MenuEntryRecord(key, pkg, cls, id);
        equippedKeys.add(key);
        if (!byKey.containsKey(key)) byKey.put(key, rec);
        if (!pkg.isEmpty()) equippedPackages.add(pkg);
        if (!cls.isEmpty() && !"*".equals(cls) && !pkg.isEmpty()) {
            classToPackage.put(cls, pkg);
            classToPackage.put(expandClass(pkg, cls), pkg);
        }
        if (id > 0 && id != 39 && !byItemId.containsKey(Integer.valueOf(id))) {
            byItemId.put(Integer.valueOf(id), rec);
        }
    }

    /**
     * Filters unequipped trim/region ghost stock entries (e.g. Built-in Cam, Memory Seat
     * Configuration, Sound Mood Lamp, VIBE on cars that do not have them in all_menu) while
     * preserving all equipped stock entries, all backed-up entries, and any hidden keys.
     */
    public static boolean isEquippedSystemTile(String key, String pkg, String cls,
                                               MenuInventory inv, Set<String> hiddenKeys) {
        if (inv == null || !inv.hasAnyData()) return true;
        if (matchingHiddenKey(hiddenKeys, pkg, cls, -1) != null) return true;
        if (inv.equippedKeys.contains(key)) return true;
        String p = cleanToken(pkg);
        String c = cleanToken(cls);
        if (!p.isEmpty()) {
            if (inv.equippedKeys.contains(p + "/*")) return true;
            if ((c.isEmpty() || "*".equals(c)) && inv.equippedPackages.contains(p)) return true;
        }
        return false;
    }

    @SuppressWarnings("unchecked")
    public static Config parseConfig(String jsonText, boolean exists) {
        if (jsonText == null || jsonText.trim().isEmpty()) {
            return new Config(false, true, Collections.<String>emptySet());
        }
        Map<String, Object> m = parseObject(jsonText);
        boolean configured = exists;
        if (m.containsKey("configured")) {
            configured = Boolean.TRUE.equals(m.get("configured"));
        }
        boolean compact = !Boolean.FALSE.equals(m.get("compact_gaps"));
        Set<String> hidden = new LinkedHashSet<String>();
        Object rawHidden = m.get("hidden");
        if (rawHidden instanceof List) {
            for (Object o : (List<Object>) rawHidden) {
                if (o == null) continue;
                String k = normKeyString(String.valueOf(o));
                if (!k.isEmpty()) hidden.add(k);
            }
        } else if (m.get("hidden_keys") instanceof String) {
            for (String part : ((String) m.get("hidden_keys")).split(",")) {
                String k = normKeyString(part);
                if (!k.isEmpty()) hidden.add(k);
            }
        }
        return new Config(configured, compact, hidden);
    }

    public static String toConfigJson(Config cfg) {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("configured", Boolean.valueOf(cfg.exists));
        out.put("compact_gaps", Boolean.valueOf(cfg.compactGaps));
        out.put("hidden", new ArrayList<Object>(cfg.hidden));
        return writeCompact(out);
    }

    public static Config readFileConfig(File file) {
        if (file == null || !file.isFile()) {
            return new Config(false, true, Collections.<String>emptySet());
        }
        try {
            return parseConfig(readUtf8(file), true);
        } catch (Exception ignored) {
            return new Config(false, true, Collections.<String>emptySet());
        }
    }

    /**
     * Synchronises one profile's all_menu JSON against hidden-tile settings, backing up
     * hidden stock and mod tiles with their exact itemId/slot and restoring them when
     * unhidden (provided their activity is still installed when installedKeys != null).
     */
    @SuppressWarnings("unchecked")
    public static SyncResult syncProfileMenu(String user, String menuJson, Config cfg,
            Set<String> installedKeys, Map<String, Object> allBackups) {
        List<String> logs = new ArrayList<String>();
        Map<String, Object> root = parseObject(menuJson);
        Object rawArr = root.get("menuItems");
        if (!(rawArr instanceof List)) return new SyncResult(null, false, logs);
        List<Object> arr = (List<Object>) rawArr;

        Map<String, Object> profiles = ensureMap(allBackups, "profiles");
        Map<String, Object> userBackup = ensureMap(profiles, user);

        boolean changed = false;
        boolean backupChanged = false;

        // 1. Back up and clear any currently-placed tile that is marked hidden.
        //    If a backup already exists for this tile (e.g. HmgApi.syncSavedMenu temporarily
        //    re-inserted a hidden mod tile into a different empty slot on boot), preserve the
        //    user's original backed-up slot!
        for (int k = 0; k < arr.size(); k++) {
            if (!(arr.get(k) instanceof Map)) continue;
            Map<String, Object> it = (Map<String, Object>) arr.get(k);
            String pkg = str(it.get("packageName"));
            String cls = str(it.get("className"));
            int id = intVal(it.get("itemId"), -1);
            if (isEmptySlot(pkg, cls, id)) continue;
            String matchedKey = matchingHiddenKey(cfg.hidden, pkg, cls, id);
            if (matchedKey == null) continue;

            Object existingObj = userBackup.get(matchedKey);
            if (existingObj instanceof Map) {
                Map<String, Object> existingBak = (Map<String, Object>) existingObj;
                int prevId = intVal(existingBak.get("itemId"), -1);
                if (id >= 9000 && prevId != id) {
                    existingBak.put("itemId", Long.valueOf(id));
                    backupChanged = true;
                }
            } else {
                Map<String, Object> bak = new LinkedHashMap<String, Object>();
                bak.put("packageName", pkg);
                bak.put("itemId", Long.valueOf(id));
                bak.put("className", cls);
                bak.put("slot", Long.valueOf(k));
                userBackup.put(matchedKey, bak);
                backupChanged = true;
            }

            arr.set(k, emptySlotMap());
            changed = true;
            logs.add("saved menu (" + user + "): hid " + matchedKey + ", slot " + (k + 1));
        }

        // 2. Collect all non-empty keys currently placed on the grid
        Set<String> presentKeys = new LinkedHashSet<String>();
        Set<String> presentClasses = new LinkedHashSet<String>();
        Set<Integer> presentItemIds = new LinkedHashSet<Integer>();
        for (int k = 0; k < arr.size(); k++) {
            if (!(arr.get(k) instanceof Map)) continue;
            Map<String, Object> it = (Map<String, Object>) arr.get(k);
            String pkg = str(it.get("packageName"));
            String cls = str(it.get("className"));
            int id = intVal(it.get("itemId"), -1);
            if (isEmptySlot(pkg, cls, id)) continue;
            presentKeys.add(normKey(pkg, cls, id));
            if (!cls.isEmpty()) presentClasses.add(cls);
            if (id > 0 && id != 39) presentItemIds.add(Integer.valueOf(id));
        }

        // 3. Restore unhidden tiles from backup (pruning any whose package was uninstalled)
        for (String key : new ArrayList<String>(userBackup.keySet())) {
            Object rawBak = userBackup.get(key);
            if (!(rawBak instanceof Map)) {
                userBackup.remove(key);
                backupChanged = true;
                continue;
            }
            Map<String, Object> bak = (Map<String, Object>) rawBak;
            String bPkg = str(bak.get("packageName"));
            String bCls = str(bak.get("className"));
            int bId = intVal(bak.get("itemId"), 0);
            if (cfg.hidden.contains(key) || matchingHiddenKey(cfg.hidden, bPkg, bCls, bId) != null) {
                continue;
            }
            String exactBakKey = normKey(bPkg, bCls, bId);
            if (installedKeys != null && !installedKeys.contains(key) && !installedKeys.contains(exactBakKey)
                    && (bPkg.isEmpty() || !installedKeys.contains(bPkg + "/*"))) {
                userBackup.remove(key);
                backupChanged = true;
                logs.add("saved menu (" + user + "): dropped backup for uninstalled " + key);
                continue;
            }
            if (presentKeys.contains(key) || (!exactBakKey.isEmpty() && presentKeys.contains(exactBakKey))
                    || (!bCls.isEmpty() && !"Empty view".equals(bCls) && presentClasses.contains(bCls))
                    || (bPkg.isEmpty() && bCls.isEmpty() && bId > 0 && presentItemIds.contains(Integer.valueOf(bId)))) {
                userBackup.remove(key);
                backupChanged = true;
                continue;
            }
            int prefSlot = intVal(bak.get("slot"), -1);
            int target = (prefSlot >= 0 && prefSlot < arr.size() && slotEmpty(arr.get(prefSlot)))
                    ? prefSlot : firstEmptySlot(arr);
            if (target < 0) {
                logs.add("saved menu (" + user + "): no empty slot to restore " + key);
                continue;
            }
            Map<String, Object> restored = new LinkedHashMap<String, Object>();
            restored.put("packageName", bPkg);
            restored.put("itemId", Long.valueOf(bId));
            restored.put("className", bCls);
            arr.set(target, restored);
            if (!exactBakKey.isEmpty()) presentKeys.add(exactBakKey);
            presentKeys.add(key);
            if (!bCls.isEmpty()) presentClasses.add(bCls);
            if (bId > 0) presentItemIds.add(Integer.valueOf(bId));
            userBackup.remove(key);
            backupChanged = true;
            changed = true;
            logs.add("saved menu (" + user + "): restored " + key + " in slot " + (target + 1));
        }

        // 4. Close empty gaps between remaining tiles when configured
        if (cfg.exists && cfg.compactGaps) {
            List<Object> nonEmpty = new ArrayList<Object>();
            for (int k = 0; k < arr.size(); k++) {
                if (!slotEmpty(arr.get(k))) nonEmpty.add(arr.get(k));
            }
            boolean hasGap = false;
            for (int k = 0; k < nonEmpty.size(); k++) {
                if (slotEmpty(arr.get(k))) {
                    hasGap = true;
                    break;
                }
            }
            if (hasGap) {
                int total = arr.size();
                arr.clear();
                arr.addAll(nonEmpty);
                while (arr.size() < total) arr.add(emptySlotMap());
                changed = true;
                logs.add("saved menu (" + user + "): compacted empty tile gaps");
            }
        }

        if (userBackup.isEmpty() && profiles.containsKey(user)) {
            profiles.remove(user);
            backupChanged = true;
        }

        return new SyncResult(changed ? writeCompact(root) : null, backupChanged, logs);
    }

    @SuppressWarnings("unchecked")
    static boolean slotEmpty(Object o) {
        if (!(o instanceof Map)) return true;
        Map<String, Object> m = (Map<String, Object>) o;
        return isEmptySlot(str(m.get("packageName")), str(m.get("className")), intVal(m.get("itemId"), -1));
    }

    static int firstEmptySlot(List<Object> arr) {
        for (int k = 0; k < arr.size(); k++) {
            if (slotEmpty(arr.get(k))) return k;
        }
        return -1;
    }

    public static Map<String, Object> emptySlotMap() {
        Map<String, Object> it = new LinkedHashMap<String, Object>();
        it.put("packageName", "Empty view");
        it.put("itemId", Long.valueOf(39));
        it.put("className", "Empty view");
        return it;
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> ensureMap(Map<String, Object> parent, String key) {
        Object cur = parent.get(key);
        if (cur instanceof Map) return (Map<String, Object>) cur;
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        parent.put(key, m);
        return m;
    }

    public static String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }

    public static int intVal(Object o, int def) {
        if (o instanceof Number) return ((Number) o).intValue();
        if (o != null) {
            try { return Integer.parseInt(String.valueOf(o).trim()); } catch (Exception ignored) {}
        }
        return def;
    }

    public static String readUtf8(File f) throws IOException {
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

    public static void writeAtomic(File dst, String content) throws IOException {
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

    // ---- Self-contained minimal RFC 8259 JSON parser & single-line writer ----

    @SuppressWarnings("unchecked")
    public static Map<String, Object> parseObject(String s) {
        Object v = new Parser(s == null ? "{}" : s).parseValue();
        if (!(v instanceof Map)) throw new IllegalArgumentException("expected JSON object");
        return (Map<String, Object>) v;
    }

    @SuppressWarnings("unchecked")
    public static String writeCompact(Object v) {
        StringBuilder b = new StringBuilder();
        appendCompact(b, v);
        return b.toString();
    }

    @SuppressWarnings("unchecked")
    private static void appendCompact(StringBuilder b, Object v) {
        if (v == null) {
            b.append("null");
        } else if (v instanceof String) {
            quoteCompact(b, (String) v);
        } else if (v instanceof Boolean || v instanceof Number) {
            b.append(v);
        } else if (v instanceof Map) {
            b.append('{');
            int n = 0;
            for (Map.Entry<String, Object> e : ((Map<String, Object>) v).entrySet()) {
                if (n++ > 0) b.append(',');
                quoteCompact(b, e.getKey());
                b.append(':');
                appendCompact(b, e.getValue());
            }
            b.append('}');
        } else if (v instanceof List) {
            b.append('[');
            List<Object> a = (List<Object>) v;
            for (int k = 0; k < a.size(); k++) {
                if (k > 0) b.append(',');
                appendCompact(b, a.get(k));
            }
            b.append(']');
        } else {
            quoteCompact(b, String.valueOf(v));
        }
    }

    private static void quoteCompact(StringBuilder b, String s) {
        b.append('"');
        for (int k = 0; k < s.length(); k++) {
            char c = s.charAt(k);
            switch (c) {
                case '"': b.append("\\\""); break;
                case '\\': b.append("\\\\"); break;
                case '\n': b.append("\\n"); break;
                case '\t': b.append("\\t"); break;
                case '\r': b.append("\\r"); break;
                default:
                    if (c < 0x20) b.append(String.format("\\u%04x", (int) c));
                    else b.append(c);
            }
        }
        b.append('"');
    }

    private static final class Parser {
        private final String s;
        private int i;

        Parser(String s) { this.s = s; }

        private void ws() {
            while (i < s.length()) {
                char c = s.charAt(i);
                if (c == ' ' || c == '\t' || c == '\r' || c == '\n') i++;
                else break;
            }
        }

        Object parseValue() {
            ws();
            if (i >= s.length()) throw new IllegalArgumentException("unexpected end of JSON");
            char c = s.charAt(i);
            if (c == '{') return parseObj();
            if (c == '[') return parseArr();
            if (c == '"') return parseStr();
            if (c == 't' && s.startsWith("true", i)) { i += 4; return Boolean.TRUE; }
            if (c == 'f' && s.startsWith("false", i)) { i += 5; return Boolean.FALSE; }
            if (c == 'n' && s.startsWith("null", i)) { i += 4; return null; }
            return parseNum();
        }

        private Map<String, Object> parseObj() {
            i++; // '{'
            Map<String, Object> m = new LinkedHashMap<String, Object>();
            ws();
            if (i < s.length() && s.charAt(i) == '}') { i++; return m; }
            while (true) {
                ws();
                String k = parseStr();
                ws();
                if (i >= s.length() || s.charAt(i) != ':') throw new IllegalArgumentException("expected ':'");
                i++;
                m.put(k, parseValue());
                ws();
                if (i >= s.length()) throw new IllegalArgumentException("unclosed '{'");
                char c = s.charAt(i++);
                if (c == '}') return m;
                if (c != ',') throw new IllegalArgumentException("expected ',' or '}'");
            }
        }

        private List<Object> parseArr() {
            i++; // '['
            List<Object> a = new ArrayList<Object>();
            ws();
            if (i < s.length() && s.charAt(i) == ']') { i++; return a; }
            while (true) {
                a.add(parseValue());
                ws();
                if (i >= s.length()) throw new IllegalArgumentException("unclosed '['");
                char c = s.charAt(i++);
                if (c == ']') return a;
                if (c != ',') throw new IllegalArgumentException("expected ',' or ']'");
            }
        }

        private String parseStr() {
            if (i >= s.length() || s.charAt(i) != '"') throw new IllegalArgumentException("expected '\"'");
            i++;
            StringBuilder b = new StringBuilder();
            while (i < s.length()) {
                char c = s.charAt(i++);
                if (c == '"') return b.toString();
                if (c == '\\') {
                    if (i >= s.length()) throw new IllegalArgumentException("bad escape");
                    char e = s.charAt(i++);
                    switch (e) {
                        case '"': case '\\': case '/': b.append(e); break;
                        case 'b': b.append('\b'); break;
                        case 'f': b.append('\f'); break;
                        case 'n': b.append('\n'); break;
                        case 'r': b.append('\r'); break;
                        case 't': b.append('\t'); break;
                        case 'u':
                            if (i + 4 > s.length()) throw new IllegalArgumentException("bad unicode escape");
                            b.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                            i += 4;
                            break;
                        default:
                            b.append(e);
                            break;
                    }
                } else {
                    b.append(c);
                }
            }
            throw new IllegalArgumentException("unclosed string");
        }

        private Number parseNum() {
            int start = i;
            if (i < s.length() && s.charAt(i) == '-') i++;
            boolean real = false;
            while (i < s.length()) {
                char c = s.charAt(i);
                if (c >= '0' && c <= '9') { i++; }
                else if (c == '.' || c == 'e' || c == 'E' || c == '+') { real = true; i++; }
                else if (c == '-' && i > start) { real = true; i++; }
                else break;
            }
            String num = s.substring(start, i);
            if (num.isEmpty() || "-".equals(num)) throw new IllegalArgumentException("bad number");
            if (real) return Double.valueOf(num);
            return Long.valueOf(num);
        }
    }
}
