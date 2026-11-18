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

    public static final int CATALOG_SCHEMA = 5;

    public static final String ACTION_TILES_CHANGED = "dev.lemoyne.tiles.ACTION_TILES_CHANGED";
    public static final String ACTION_REQUEST_CATALOG = "dev.lemoyne.tiles.ACTION_REQUEST_CATALOG";
    public static final String ACTION_OPEN_STOCK_EDITOR = "dev.lemoyne.tiles.ACTION_OPEN_STOCK_EDITOR";
    public static final String EMPTY_SLOT_TOKEN = "@empty";
    public static final String PROVIDER_AUTH = "dev.lemoyne.tiles.provider";

    public static final String BACKUP_FILE = "hmgapi-tile-manager-backup.json";
    public static final String LEGACY_BACKUP_FILE = "hmgapi-hidden-backup.json";
    public static final String LEGACY_PUBLIC_CONFIG = "/data/hmgapi/public/launcher-tiles.json";

    public static final class ProfileSlot {
        public final int index;
        public final boolean empty;
        public final String key;
        public final String packageName;
        public final String className;
        public final int itemId;

        public ProfileSlot(int index, boolean empty, String key, String packageName, String className, int itemId) {
            this.index = index;
            this.empty = empty;
            this.key = key == null ? "" : key;
            this.packageName = packageName == null ? "" : packageName;
            this.className = className == null ? "" : className;
            this.itemId = itemId;
        }
    }

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
        public final List<MenuEntryRecord> allNonGuestEntries;
        public final Set<String> equippedKeys;
        public final Set<String> equippedPackages;
        public final Map<String, String> classToPackage;
        public final Map<Integer, MenuEntryRecord> byItemId;
        public final Set<String> backedUpKeys;

        public MenuInventory(List<MenuEntryRecord> activeEntries, Set<String> equippedKeys,
                             Set<String> equippedPackages, Map<String, String> classToPackage,
                             Map<Integer, MenuEntryRecord> byItemId) {
            this(activeEntries, equippedKeys, equippedPackages, classToPackage, byItemId,
                    Collections.<String>emptySet(), activeEntries);
        }

        public MenuInventory(List<MenuEntryRecord> activeEntries, Set<String> equippedKeys,
                             Set<String> equippedPackages, Map<String, String> classToPackage,
                             Map<Integer, MenuEntryRecord> byItemId, Set<String> backedUpKeys) {
            this(activeEntries, equippedKeys, equippedPackages, classToPackage, byItemId,
                    backedUpKeys, activeEntries);
        }

        public MenuInventory(List<MenuEntryRecord> activeEntries, Set<String> equippedKeys,
                             Set<String> equippedPackages, Map<String, String> classToPackage,
                             Map<Integer, MenuEntryRecord> byItemId, Set<String> backedUpKeys,
                             List<MenuEntryRecord> allNonGuestEntries) {
            this.activeEntries = Collections.unmodifiableList(activeEntries);
            this.allNonGuestEntries = Collections.unmodifiableList(
                    allNonGuestEntries == null ? activeEntries : allNonGuestEntries);
            this.equippedKeys = Collections.unmodifiableSet(equippedKeys);
            this.equippedPackages = Collections.unmodifiableSet(equippedPackages);
            this.classToPackage = Collections.unmodifiableMap(classToPackage);
            this.byItemId = Collections.unmodifiableMap(byItemId);
            this.backedUpKeys = Collections.unmodifiableSet(
                    backedUpKeys == null ? Collections.<String>emptySet() : new LinkedHashSet<String>(backedUpKeys));
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

    public static final class ReconcileTile {
        public final String key;
        public final String packageName;
        public final String className;
        public final String title;
        public final int itemId;

        public ReconcileTile(String key, String packageName, String className, String title, int itemId) {
            String p = cleanToken(packageName);
            String c = cleanToken(className);
            if ("*".equals(c)) c = "";
            this.packageName = p;
            this.className = c;
            this.title = title == null ? "" : title.trim();
            this.itemId = (itemId > 0 && itemId != 39) ? itemId : 1;
            String norm = normKeyString(key);
            this.key = !norm.isEmpty() ? norm : normKey(p, c, this.itemId);
        }
    }

    public interface InstallChecker {
        boolean isBackupStillInstalled(String backupKey, String pkg, String cls, int itemId);
    }

    public interface IntStringProbe {
        String resolveStringResource(int resId);
    }

    public interface IntDrawableProbe {
        String resolveDrawableEntryName(int resId);
    }

    public interface ItemVisibilityFilter {
        boolean shouldShow(Object item);
        void onItemAdded(Object item);
    }

    /**
     * ArrayList wrapper installed on launcher.menu_list.items and homesetting.edit_list.items
     * before HMG-API's own afterHookedMethod runs (Bridge.handleHookedMethod executes after-hooks
     * in LIFO order). Any mod/custom tile appended later by HmgApi.launcher, HmgApi.editor, or
     * NEMESIS is automatically recorded in rawItems and filtered from the visible list if hidden,
     * while in-place reorders (set, remove, add-at-index, and Dalvik ArrayListIterator.set during
     * Collections.sort) stay synchronised into rawItems without duplicating or dropping hidden items.
     */
    public static final class FilterableMenuList extends ArrayList<Object> {
        private static final long serialVersionUID = 1L;
        private final List<Object> rawItems = new ArrayList<Object>();
        private final List<Integer> visibleRawIndices = new ArrayList<Integer>();
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
            visibleRawIndices.clear();
            for (Object e : copy) {
                if (indexOfIdentity(rawItems, e) < 0) {
                    rawItems.add(e);
                }
            }
            return rebuildVisibleLocked();
        }

        public synchronized int refilter(ItemVisibilityFilter nextFilter) {
            syncVisibleOrderIntoRawLocked();
            if (nextFilter != null) this.filter = nextFilter;
            return rebuildVisibleLocked();
        }

        /**
         * Propagates any direct super-array element replacements (such as Android 4.4 KitKat
         * ArrayList$ArrayListIterator.set invoked by Collections.sort) back into rawItems before
         * rebuilding or snapshotting.
         */
        private void syncVisibleOrderIntoRawLocked() {
            if (visibleRawIndices.size() != super.size()) return;
            for (int i = 0; i < visibleRawIndices.size(); i++) {
                int rawIdx = visibleRawIndices.get(i).intValue();
                if (rawIdx >= 0 && rawIdx < rawItems.size()) {
                    rawItems.set(rawIdx, super.get(i));
                }
            }
        }

        private int rebuildVisibleLocked() {
            internalRefiltering = true;
            int hiddenCount = 0;
            try {
                super.clear();
                visibleRawIndices.clear();
                for (int i = 0; i < rawItems.size(); i++) {
                    Object item = rawItems.get(i);
                    if (filter != null) filter.onItemAdded(item);
                    if (filter == null || filter.shouldShow(item)) {
                        super.add(item);
                        visibleRawIndices.add(Integer.valueOf(i));
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
            syncVisibleOrderIntoRawLocked();
            return new ArrayList<Object>(rawItems);
        }

        @Override
        public synchronized boolean add(Object e) {
            if (internalRefiltering) return super.add(e);
            syncVisibleOrderIntoRawLocked();
            if (indexOfIdentity(rawItems, e) >= 0) {
                return false;
            }
            int rawIdx = rawItems.size();
            rawItems.add(e);
            if (filter != null) filter.onItemAdded(e);
            if (filter != null && !filter.shouldShow(e)) {
                return false;
            }
            visibleRawIndices.add(Integer.valueOf(rawIdx));
            return super.add(e);
        }

        @Override
        public synchronized void add(int index, Object element) {
            if (internalRefiltering) {
                super.add(index, element);
                return;
            }
            syncVisibleOrderIntoRawLocked();
            int existingRaw = indexOfIdentity(rawItems, element);
            if (existingRaw >= 0) {
                rawItems.remove(existingRaw);
                int existingVis = indexOfIdentity(this, element);
                if (existingVis >= 0) {
                    super.remove(existingVis);
                    if (existingVis < visibleRawIndices.size()) {
                        visibleRawIndices.remove(existingVis);
                    }
                    if (index > existingVis) index--;
                }
                for (int i = 0; i < visibleRawIndices.size(); i++) {
                    int cur = visibleRawIndices.get(i).intValue();
                    if (cur > existingRaw) visibleRawIndices.set(i, Integer.valueOf(cur - 1));
                }
            }
            int safeIndex = Math.max(0, Math.min(index, super.size()));
            int rawInsertIdx = (safeIndex < visibleRawIndices.size())
                    ? visibleRawIndices.get(safeIndex).intValue()
                    : rawItems.size();
            rawItems.add(rawInsertIdx, element);
            for (int i = 0; i < visibleRawIndices.size(); i++) {
                int cur = visibleRawIndices.get(i).intValue();
                if (cur >= rawInsertIdx) visibleRawIndices.set(i, Integer.valueOf(cur + 1));
            }
            if (filter != null) filter.onItemAdded(element);
            if (filter != null && !filter.shouldShow(element)) {
                return;
            }
            super.add(safeIndex, element);
            visibleRawIndices.add(safeIndex, Integer.valueOf(rawInsertIdx));
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
            int beforeSize = super.size();
            for (Object e : c) {
                int prev = super.size();
                add(cur, e);
                if (super.size() > prev) cur++;
            }
            return super.size() != beforeSize;
        }

        @Override
        public synchronized Object set(int index, Object element) {
            if (internalRefiltering) return super.set(index, element);
            syncVisibleOrderIntoRawLocked();
            Object prev = super.get(index);
            int rawIdx = (index >= 0 && index < visibleRawIndices.size())
                    ? visibleRawIndices.get(index).intValue()
                    : indexOfIdentityOrEquals(rawItems, prev);
            if (filter != null) filter.onItemAdded(element);
            boolean show = (filter == null || filter.shouldShow(element));
            if (rawIdx >= 0 && rawIdx < rawItems.size()) {
                rawItems.set(rawIdx, element);
            } else {
                rawItems.add(element);
                rawIdx = rawItems.size() - 1;
            }
            if (show) {
                return super.set(index, element);
            }
            Object removed = super.remove(index);
            if (index >= 0 && index < visibleRawIndices.size()) {
                visibleRawIndices.remove(index);
            }
            return removed;
        }

        @Override
        public synchronized Object remove(int index) {
            if (internalRefiltering) return super.remove(index);
            syncVisibleOrderIntoRawLocked();
            Object removed = super.remove(index);
            int rawIdx = (index >= 0 && index < visibleRawIndices.size())
                    ? visibleRawIndices.remove(index).intValue()
                    : indexOfIdentityOrEquals(rawItems, removed);
            if (rawIdx >= 0 && rawIdx < rawItems.size()) {
                rawItems.remove(rawIdx);
                for (int i = 0; i < visibleRawIndices.size(); i++) {
                    int cur = visibleRawIndices.get(i).intValue();
                    if (cur > rawIdx) visibleRawIndices.set(i, Integer.valueOf(cur - 1));
                }
            }
            return removed;
        }

        @Override
        public synchronized boolean remove(Object o) {
            if (internalRefiltering) return super.remove(o);
            syncVisibleOrderIntoRawLocked();
            int visIdx = indexOfIdentityOrEquals(this, o);
            if (visIdx >= 0) {
                remove(visIdx);
                return true;
            }
            int rawIdx = indexOfIdentityOrEquals(rawItems, o);
            if (rawIdx >= 0) {
                rawItems.remove(rawIdx);
                for (int i = 0; i < visibleRawIndices.size(); i++) {
                    int cur = visibleRawIndices.get(i).intValue();
                    if (cur > rawIdx) visibleRawIndices.set(i, Integer.valueOf(cur - 1));
                }
                return true;
            }
            return false;
        }

        @Override
        public synchronized void clear() {
            if (!internalRefiltering) {
                rawItems.clear();
                visibleRawIndices.clear();
            }
            super.clear();
        }

        @Override
        public synchronized boolean removeAll(Collection<?> c) {
            if (c == null || c.isEmpty()) return false;
            if (internalRefiltering) return super.removeAll(c);
            syncVisibleOrderIntoRawLocked();
            boolean changed = false;
            for (Object o : c) {
                while (remove(o)) {
                    changed = true;
                }
            }
            return changed;
        }

        @Override
        public synchronized boolean retainAll(Collection<?> c) {
            if (c == null) return false;
            if (internalRefiltering) return super.retainAll(c);
            syncVisibleOrderIntoRawLocked();
            boolean changed = false;
            for (int i = super.size() - 1; i >= 0; i--) {
                if (!c.contains(super.get(i))) {
                    remove(i);
                    changed = true;
                }
            }
            return changed;
        }

        private static int indexOfIdentity(List<?> list, Object target) {
            if (list == null) return -1;
            for (int i = 0; i < list.size(); i++) {
                if (list.get(i) == target) return i;
            }
            return -1;
        }

        private static int indexOfIdentityOrEquals(List<?> list, Object target) {
            int idx = indexOfIdentity(list, target);
            if (idx >= 0 || list == null) return idx;
            for (int i = 0; i < list.size(); i++) {
                Object cur = list.get(i);
                if (target == null ? cur == null : target.equals(cur)) return i;
            }
            return -1;
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
            public final int iconId;
            public final int titleId;
            public final String titleText;
            public final int matchedItemId;

            public Extracted(String pkg, String cls, int titleId, String titleText, int matchedItemId) {
                this(pkg, cls, 0, titleId, titleText, matchedItemId);
            }

            public Extracted(String pkg, String cls, int iconId, int titleId, String titleText, int matchedItemId) {
                this.pkg = cleanToken(pkg);
                this.cls = cleanToken(cls);
                this.iconId = iconId;
                this.titleId = titleId;
                this.titleText = titleText == null ? "" : titleText.trim();
                this.matchedItemId = matchedItemId;
            }
        }

        private Set<Field> pkgCandidates;
        private Set<Field> clsCandidates;
        private Set<Field> compCandidates;
        private Set<Field> iconIdCandidates;
        private Set<Field> iconDisabledIdCandidates;
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

            if (iconId != 0 && iconId != iconDisabledId && iconId != titleId) {
                Set<Field> foundIco = new LinkedHashSet<Field>();
                for (Field f : fields) {
                    Class<?> t = f.getType();
                    if (t == int.class || t == Integer.class) {
                        Object val = readField(f, instance);
                        if (val instanceof Number && ((Number) val).intValue() == iconId) {
                            foundIco.add(f);
                        }
                    }
                }
                if (!foundIco.isEmpty()) iconIdCandidates = narrow(iconIdCandidates, foundIco);
            }

            if (iconDisabledId != 0 && iconDisabledId != iconId && iconDisabledId != titleId) {
                Set<Field> foundDis = new LinkedHashSet<Field>();
                for (Field f : fields) {
                    Class<?> t = f.getType();
                    if (t == int.class || t == Integer.class) {
                        Object val = readField(f, instance);
                        if (val instanceof Number && ((Number) val).intValue() == iconDisabledId) {
                            foundDis.add(f);
                        }
                    }
                }
                if (!foundDis.isEmpty()) iconDisabledIdCandidates = narrow(iconDisabledIdCandidates, foundDis);
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
            return extract(item, inv, alreadyMatchedKeys, stringProbe, null);
        }

        public synchronized Extracted extract(Object item, MenuInventory inv,
                                              Set<String> alreadyMatchedKeys,
                                              IntStringProbe stringProbe,
                                              IntDrawableProbe drawableProbe) {
            if (item == null) return null;
            String pkg = "";
            String cls = "";
            int iconId = 0;
            int titleId = 0;
            String titleText = "";
            int matchedItemId = -1;

            Field pf = firstApplicable(pkgCandidates, item);
            Field cf = firstApplicable(clsCandidates, item);
            Field compF = firstApplicable(compCandidates, item);
            Field icoF = firstApplicable(iconIdCandidates, item);
            Field disF = firstApplicable(iconDisabledIdCandidates, item);
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
            if (icoF != null) {
                Object v = readField(icoF, item);
                if (v instanceof Number) {
                    int candidate = ((Number) v).intValue();
                    if ((candidate & 0xffff0000) != 0x7f7e0000) iconId = candidate;
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
            if (iconId == 0) {
                Integer iid = firstIntGetter(item, "getIconId", "getIconResId", "getIconResource", "getImageId", "getImgId");
                if (iid != null && (iid.intValue() & 0xffff0000) != 0x7f7e0000) iconId = iid.intValue();
            }
            if (titleId == 0) {
                Integer tid = firstIntGetter(item, "getTitleId", "getTitleResId", "getStringId");
                if (tid != null) titleId = tid.intValue();
            }
            if (titleText.isEmpty()) {
                titleText = firstNonEmptyGetter(item, "getTitle", "getLabel", "getAppName");
            }

            // 3. Field scan for ComponentName, Intent, String pkg/cls/title, and string/drawable resource IDs
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

            // Probe int fields for R.drawable.* / R.mipmap.* enabled icon resource IDs
            if (iconId == 0 && drawableProbe != null) {
                int fallbackDisId = 0;
                for (Field f : allFields) {
                    if (f.equals(tidF) || f.equals(disF)) continue;
                    Class<?> t = f.getType();
                    if (t != int.class && t != Integer.class) continue;
                    Object val = readField(f, item);
                    if (!(val instanceof Number)) continue;
                    int id = ((Number) val).intValue();
                    if ((id & 0xff000000) != 0x7f000000 || (id & 0xffff0000) == 0x7f7e0000) continue;
                    if (id == titleId) continue;
                    String entryName = drawableProbe.resolveDrawableEntryName(id);
                    if (entryName != null && !entryName.trim().isEmpty()) {
                        if (!looksLikeDisabledDrawableName(entryName)) {
                            iconId = id;
                            break;
                        } else if (fallbackDisId == 0) {
                            fallbackDisId = id;
                        }
                    }
                }
                if (iconId == 0) iconId = fallbackDisId;
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
                Set<Integer> seenIds = new LinkedHashSet<Integer>();
                MenuEntryRecord uniqueMatch = null;
                int matches = 0;
                for (Field f : allFields) {
                    Class<?> t = f.getType();
                    if (t != int.class && t != Integer.class) continue;
                    Object val = readField(f, item);
                    if (!(val instanceof Number)) continue;
                    int n = ((Number) val).intValue();
                    if (n <= 0 || n == 39 || n >= 9000 || !seenIds.add(Integer.valueOf(n))) continue;
                    MenuEntryRecord rec = inv.byItemId.get(Integer.valueOf(n));
                    if (rec != null && !isRecordAlreadyMatched(rec, alreadyMatchedKeys)) {
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
            return new Extracted(pkg, cls, iconId, titleId, titleText, matchedItemId);
        }

        private static boolean looksLikeDisabledDrawableName(String entryName) {
            if (entryName == null) return false;
            String low = entryName.trim().toLowerCase(Locale.US);
            return low.endsWith("_dis") || low.endsWith("_disabled") || low.endsWith("_disable")
                    || low.endsWith("_dim") || low.endsWith("_off") || low.endsWith("_d")
                    || low.contains("_dis_") || low.contains("_disabled_");
        }

        private static boolean isRecordAlreadyMatched(MenuEntryRecord rec, Set<String> alreadyMatchedKeys) {
            if (alreadyMatchedKeys == null || alreadyMatchedKeys.isEmpty()) return false;
            if (alreadyMatchedKeys.contains(rec.key)) return true;
            if (!rec.packageName.isEmpty()) {
                if (alreadyMatchedKeys.contains(rec.packageName + "/*")) return true;
                if ("com.android.settings".equals(rec.packageName)) {
                    return (rec.className.isEmpty() || "*".equals(rec.className))
                            && alreadyMatchedKeys.contains("com.android.settings/com.android.settings.Settings");
                }
                if (rec.className.isEmpty() || "*".equals(rec.className)) {
                    String prefix = rec.packageName + "/";
                    for (String k : alreadyMatchedKeys) {
                        if (k.startsWith(prefix)) return true;
                    }
                }
            }
            return false;
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

    /**
     * Selects active driver profile ALL_MENU JSONs while avoiding unpruned factory Guest/Driver 2
     * rows: unions custom-renamed owner profiles (e.g. Bluelink/Kia Connect profile names) with
     * Driver 1 so neither driver shadows the other, falling back to non-Guest profiles if Driver 1
     * has no non-empty tiles, then all profiles.
     */
    public static List<String> selectProfileMenuJsons(Map<String, String> menusByUser) {
        if (menusByUser == null || menusByUser.isEmpty()) return Collections.emptyList();
        List<String> customNamed = new ArrayList<String>();
        List<String> driver1 = new ArrayList<String>();
        List<String> nonGuest = new ArrayList<String>();
        List<String> all = new ArrayList<String>();
        for (Map.Entry<String, String> e : menusByUser.entrySet()) {
            String user = cleanToken(e.getKey());
            String json = e.getValue();
            if (json == null || json.trim().isEmpty()) continue;
            all.add(json);
            if ("Guest".equalsIgnoreCase(user)) continue;
            nonGuest.add(json);
            if (isDriver1ProfileName(user)) {
                driver1.add(json);
            } else if (!user.isEmpty() && !user.startsWith("#") && !isSecondaryDefaultProfileName(user)) {
                customNamed.add(json);
            }
        }
        List<String> ownerProfiles = new ArrayList<String>(customNamed.size() + driver1.size());
        ownerProfiles.addAll(customNamed);
        ownerProfiles.addAll(driver1);
        if (!ownerProfiles.isEmpty() && hasAnyPlacedMenuTile(ownerProfiles)) {
            return ownerProfiles;
        }
        if (!nonGuest.isEmpty()) return nonGuest;
        return all;
    }

    public static List<String> collectNonGuestMenuJsons(Map<String, String> menusByUser) {
        if (menusByUser == null || menusByUser.isEmpty()) return Collections.emptyList();
        List<String> nonGuest = new ArrayList<String>();
        List<String> all = new ArrayList<String>();
        for (Map.Entry<String, String> e : menusByUser.entrySet()) {
            String user = cleanToken(e.getKey());
            String json = e.getValue();
            if (json == null || json.trim().isEmpty()) continue;
            all.add(json);
            if (!"Guest".equalsIgnoreCase(user)) nonGuest.add(json);
        }
        return !nonGuest.isEmpty() ? nonGuest : all;
    }

    private static boolean isDriver1ProfileName(String user) {
        return "Driver 1".equalsIgnoreCase(user) || "Driver1".equalsIgnoreCase(user)
                || "User 1".equalsIgnoreCase(user) || "User1".equalsIgnoreCase(user);
    }

    private static boolean isSecondaryDefaultProfileName(String user) {
        return "Driver 2".equalsIgnoreCase(user) || "Driver2".equalsIgnoreCase(user)
                || "User 2".equalsIgnoreCase(user) || "User2".equalsIgnoreCase(user)
                || "Driver 3".equalsIgnoreCase(user) || "Driver3".equalsIgnoreCase(user)
                || "User 3".equalsIgnoreCase(user) || "User3".equalsIgnoreCase(user);
    }

    @SuppressWarnings("unchecked")
    private static boolean hasAnyPlacedMenuTile(List<String> menuJsons) {
        if (menuJsons == null) return false;
        for (String menuJson : menuJsons) {
            if (menuJson == null || menuJson.trim().isEmpty()) continue;
            try {
                Map<String, Object> root = parseObject(menuJson);
                Object rawArr = root.get("menuItems");
                if (!(rawArr instanceof List)) continue;
                for (Object o : (List<Object>) rawArr) {
                    if (!(o instanceof Map)) continue;
                    Map<String, Object> it = (Map<String, Object>) o;
                    if (!isEmptySlot(cleanToken(it.get("packageName")),
                            cleanToken(it.get("className")), intVal(it.get("itemId"), -1))) {
                        return true;
                    }
                }
            } catch (Throwable ignored) {}
        }
        return false;
    }

    public static MenuInventory collectMenuInventory(List<String> profileMenuJsons, Map<String, Object> allBackups) {
        return collectMenuInventory(profileMenuJsons, profileMenuJsons, allBackups);
    }

    @SuppressWarnings("unchecked")
    public static MenuInventory collectMenuInventory(List<String> profileMenuJsons,
                                                     List<String> allNonGuestMenuJsons,
                                                     Map<String, Object> allBackups) {
        Map<String, MenuEntryRecord> byKey = new LinkedHashMap<String, MenuEntryRecord>();
        Map<String, MenuEntryRecord> nonGuestByKey = new LinkedHashMap<String, MenuEntryRecord>();
        Set<String> equippedKeys = new LinkedHashSet<String>();
        Set<String> equippedPackages = new LinkedHashSet<String>();
        Map<String, String> classToPackage = new LinkedHashMap<String, String>();
        Map<Integer, MenuEntryRecord> byItemId = new LinkedHashMap<Integer, MenuEntryRecord>();
        Set<String> backedUpKeys = new LinkedHashSet<String>();

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

        nonGuestByKey.putAll(byKey);
        if (allNonGuestMenuJsons != null) {
            for (String menuJson : allNonGuestMenuJsons) {
                if (menuJson == null || menuJson.trim().isEmpty()) continue;
                try {
                    Map<String, Object> root = parseObject(menuJson);
                    Object rawArr = root.get("menuItems");
                    if (!(rawArr instanceof List)) continue;
                    for (Object o : (List<Object>) rawArr) {
                        if (!(o instanceof Map)) continue;
                        Map<String, Object> it = (Map<String, Object>) o;
                        String pkg = cleanToken(it.get("packageName"));
                        String cls = cleanToken(it.get("className"));
                        int id = intVal(it.get("itemId"), -1);
                        if (isEmptySlot(pkg, cls, id)) continue;
                        String key = normKey(pkg, cls, id);
                        if (!key.isEmpty() && !nonGuestByKey.containsKey(key)) {
                            nonGuestByKey.put(key, new MenuEntryRecord(key, pkg, cls, id));
                        }
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
                        if (!bk.isEmpty()) {
                            equippedKeys.add(bk);
                            backedUpKeys.add(bk);
                        }
                        if (be.getValue() instanceof Map) {
                            Map<String, Object> bm = (Map<String, Object>) be.getValue();
                            String recKey = normKey(cleanToken(bm.get("packageName")),
                                    cleanToken(bm.get("className")), intVal(bm.get("itemId"), -1));
                            if (!recKey.isEmpty()) backedUpKeys.add(recKey);
                            recordInventoryItem(bm, byKey, equippedKeys,
                                    equippedPackages, classToPackage, byItemId);
                        }
                    }
                }
            }
        }
        for (Map.Entry<String, MenuEntryRecord> e : byKey.entrySet()) {
            if (!nonGuestByKey.containsKey(e.getKey())) {
                nonGuestByKey.put(e.getKey(), e.getValue());
            }
        }

        return new MenuInventory(new ArrayList<MenuEntryRecord>(byKey.values()),
                equippedKeys, equippedPackages, classToPackage, byItemId, backedUpKeys,
                new ArrayList<MenuEntryRecord>(nonGuestByKey.values()));
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
     * Classifies a launcher/editor entry as "package" (Packages & Mods) or "system" (System Apps).
     * All OEM /system/app packages (including Hyundai Pay, Notifications, Valet Mode, and Phone
     * Projection regardless of OEM vendor package prefix) have FLAG_SYSTEM set and classify as
     * "system" unless registered as an HMG-API mod, HMG-API app, or NEMESIS shortcut. All
     * user-installed /data/app APKs (isUserInstalledApp=true) classify as "package".
     */
    public static String classifyTileKind(String pkg, String title, int titleId, int itemId,
                                          boolean isUserInstalledApp, String modId) {
        String p = cleanToken(pkg);
        String lowPkg = p.toLowerCase(Locale.US);
        String upTitle = title == null ? "" : title.toUpperCase(Locale.US);
        boolean isHmgRes = (titleId & 0xffff0000) == 0x7f7e0000;
        boolean isHmgApp = "hmgapi.app".equals(p) || p.startsWith("hmgapi.");
        boolean isNemesis = lowPkg.contains("nemesis") || lowPkg.contains("xwtk")
                || upTitle.contains("NEMESIS") || itemId == 90;
        boolean hasMod = modId != null && !modId.trim().isEmpty();
        return (isHmgRes || isHmgApp || isNemesis || isUserInstalledApp || hasMod || itemId >= 9000)
                ? "package" : "system";
    }

    /**
     * Vehicle trim, region, and declared /system/etc/permissions feature snapshot used to filter
     * optional stock definitions compiled into shared onebin Gen5W system images (e.g. VIBE,
     * Built-in Cam, Sound Mood Lamp, and Memory Seat Configuration on trims/regions without them).
     */
    public static final class Gen5wFacts {
        public final String product;
        public final String model;
        public final String region;
        public final int buildInfoRegion;
        public final Set<String> features;
        public final Integer imsOption;
        public final Integer smlOption;
        public final Integer dvrsOption;

        public Gen5wFacts(String product, int buildInfoRegion, Set<String> features,
                          Integer imsOption, Integer smlOption, Integer dvrsOption) {
            this.product = cleanToken(product);
            String[] parts = this.product.isEmpty() ? new String[0] : this.product.split("\\.");
            this.model = parts.length > 0 ? parts[0].trim().toUpperCase(Locale.US) : "";
            this.region = parts.length > 1 ? parts[1].trim().toUpperCase(Locale.US) : "";
            this.buildInfoRegion = buildInfoRegion;
            Set<String> norm = new LinkedHashSet<String>();
            if (features != null) {
                for (String f : features) {
                    if (f != null && !f.trim().isEmpty()) {
                        norm.add(f.trim().toLowerCase(Locale.US));
                    }
                }
            }
            this.features = Collections.unmodifiableSet(norm);
            this.imsOption = imsOption;
            this.smlOption = smlOption;
            this.dvrsOption = dvrsOption;
        }

        public boolean isKorea() {
            if ("KOR".equals(region) || "KR".equals(region)) return true;
            if (!region.isEmpty()) return false;
            if (buildInfoRegion >= 0) return buildInfoRegion == 0;
            return true; // Unknown environment: stay permissive
        }

        public boolean isBaseSeTrim() {
            return model.length() > 2 && model.endsWith("SE");
        }

        public boolean isKnownNonSmlVehicle() {
            if (isBaseSeTrim()) return true;
            return model.startsWith("NEEV") || model.startsWith("CEEV") || model.startsWith("CVEV")
                    || model.startsWith("JW") || model.startsWith("JK") || model.startsWith("GV")
                    || model.startsWith("G80") || model.startsWith("G90");
        }

        public boolean hasFeatureContaining(String sub) {
            String needle = sub.toLowerCase(Locale.US);
            for (String f : features) {
                if (f.contains(needle)) return true;
            }
            return false;
        }

        public boolean hasFeatureToken(String token) {
            String t = token.toLowerCase(Locale.US);
            for (String f : features) {
                if (f.equals(t) || f.endsWith("." + t) || f.contains("." + t + ".")
                        || f.endsWith("_" + t) || f.contains("_" + t + "_")) {
                    return true;
                }
            }
            return false;
        }
    }

    public static Set<String> readPermissionXmlFeatures(File permDir) {
        Set<String> out = new LinkedHashSet<String>();
        if (permDir == null || !permDir.isDirectory()) return out;
        File[] files = permDir.listFiles();
        if (files == null) return out;
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
        return out;
    }

    /**
     * Checks whether a stock ("system") tile corresponds to an optional trim/regional feature that
     * is absent on the current Gen5W vehicle, even when shared onebin firmware or unpruned factory
     * all_menu rows still mention it. Never suppresses equipped standard stock apps.
     */
    public static boolean isSupportedStockFeature(String title, String pkg, String cls, Gen5wFacts facts) {
        if (facts == null) return true;
        String t = cleanToken(title).toLowerCase(Locale.US);
        String p = cleanToken(pkg).toLowerCase(Locale.US);
        String c = cleanToken(cls).toLowerCase(Locale.US);
        String probe = p + "/" + c;

        // 1. Naver VIBE (South Korea domestic streaming music service)
        if ("vibe".equals(t) || p.contains("naver.vibe") || p.endsWith(".vibe") || c.contains(".vibe")) {
            return facts.hasFeatureContaining("vibe") || facts.isKorea();
        }

        // 2. Built-in Cam / DVRS (dashcam hardware option; KOR-only on Gen5W unless DVRS feature present)
        if ("built-in cam".equals(t) || "built in cam".equals(t)
                || probe.contains("dvrs") || probe.contains("builtincam") || probe.contains("builtin_cam")) {
            if (facts.dvrsOption != null) return facts.dvrsOption.intValue() > 0;
            if (facts.hasFeatureContaining("dvrs") || facts.hasFeatureContaining("builtincam")) return true;
            return facts.isKorea();
        }

        // 3. Sound Mood Lamp (music-reactive door/speaker mood lamp on select Kia models; absent on E-GMP / SE)
        if ("sound mood lamp".equals(t) || probe.contains("soundmood") || probe.contains("sound_mood")
                || probe.contains("moodlamp") || probe.contains("mood_lamp")) {
            if (facts.smlOption != null) return facts.smlOption.intValue() > 0;
            if (facts.hasFeatureContaining("soundmood") || facts.hasFeatureContaining("moodlamp")
                    || facts.hasFeatureToken("sml")) {
                return true;
            }
            if (facts.isKnownNonSmlVehicle()) return false;
            return true;
        }

        // 4. Memory Seat Configuration / IMS (Integrated Memory System seat screen; absent on SE / non-IMS trims)
        if ("memory seat configuration".equals(t) || t.contains("memory seat")
                || probe.contains("memoryseat") || probe.contains("seatmemory")
                || probe.contains("imsseat") || probe.contains(".ims.")
                || c.endsWith("imsactivity") || c.endsWith("imsmainactivity") || c.contains("seatposition")) {
            if (facts.imsOption != null) return facts.imsOption.intValue() > 0;
            if (facts.hasFeatureContaining("memoryseat") || facts.hasFeatureContaining("seatmemory")
                    || facts.hasFeatureToken("ims")) {
                return true;
            }
            if (facts.isBaseSeTrim()) return false;
            return true;
        }

        return true;
    }

    /**
     * Identifies universal core Gen5W head-unit apps that are present on every vehicle whenever
     * their APK & Activity are installed and enabled in PackageManager and loaded by Launcher_WP3.
     * Allowing these unconditionally guarantees they remain visible and recoverable in Tile Manager
     * even if an earlier version or interrupted sync left their all_menu slots empty without a
     * backup. Never matches optional trim, powertrain (EV/HEV/PHEV), or regional features.
     */
    public static boolean isUnconditionalCoreStockTile(String title, String pkg, String cls) {
        String t = cleanToken(title).toLowerCase(Locale.US);
        String p = cleanToken(pkg).toLowerCase(Locale.US);
        String c = cleanToken(cls).toLowerCase(Locale.US);
        String fullCls = expandClass(p, c);
        String probe = p + "/" + fullCls;

        if (probe.contains("ims") || probe.contains("memoryseat") || probe.contains("seatmemory")
                || probe.contains("seatposition") || probe.contains("soundmood") || probe.contains("moodlamp")
                || probe.contains("dvrs") || probe.contains("builtincam") || probe.contains("vibe")
                || probe.contains("hdradio") || t.contains("hd radio") || t.contains("sirius")) {
            return false;
        }

        // 1. Main Setup / Settings activity ONLY (never optional com.android.settings sub-screens)
        if ("com.android.settings".equals(p)) {
            if ("com.android.settings.settings".equals(fullCls)) return true;
            return (fullCls.isEmpty() || "*".equals(fullCls))
                    && ("setup".equals(t) || "settings".equals(t));
        }

        // 2. Radio, Media, Phone, Phone Projection, Voice Memo, User's Manual, Notifications, Nav
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

    /**
     * Filters unequipped trim/region ghost stock entries (e.g. Built-in Cam, Memory Seat
     * Configuration, Sound Mood Lamp, VIBE, or unequipped powertrain tiles) against the vehicle's
     * active all_menu + Tile Manager slot backups across exact keys, single-tile package aliases,
     * @class/* virtual entries, and itemId correlation.
     */
    public static boolean isEquippedSystemTile(String key, String pkg, String cls,
                                               MenuInventory inv, Set<String> multiEntryPackages) {
        return isEquippedSystemTile(key, pkg, cls, -1, inv, multiEntryPackages);
    }

    public static boolean isEquippedSystemTile(String key, String pkg, String cls, int itemId,
                                               MenuInventory inv, Set<String> multiEntryPackages) {
        if (inv == null || !inv.hasAnyData()) return true;
        if (inv.equippedKeys.contains(key)) return true;
        if (itemId > 0 && itemId != 39 && inv.byItemId.containsKey(Integer.valueOf(itemId))) {
            return true;
        }
        String p = cleanToken(pkg);
        String c = cleanToken(cls);
        if (!c.isEmpty() && !"*".equals(c)) {
            String fullCls = expandClass(p, c);
            if (inv.equippedKeys.contains("@class/" + c) || inv.equippedKeys.contains("@class/" + fullCls)) {
                return true;
            }
        }
        if (!p.isEmpty()) {
            if (("com.hkmc.radio".equals(p) || p.startsWith("com.hkmc.radio."))
                    && inv.equippedKeys.contains("@class/Radio")) {
                return true;
            }
            boolean isMulti = "com.android.settings".equals(p)
                    || (multiEntryPackages != null && multiEntryPackages.contains(p));
            if (!isMulti) {
                if (inv.equippedKeys.contains(p + "/*") || inv.equippedPackages.contains(p)) {
                    return true;
                }
            } else if ("com.android.settings/com.android.settings.Settings".equals(key)
                    && inv.equippedKeys.contains("com.android.settings/*")) {
                return true;
            }
        }
        return false;
    }

    @SuppressWarnings("unchecked")
    public static Config parseConfig(String jsonText, boolean exists) {
        if (jsonText == null || jsonText.trim().isEmpty()) {
            return new Config(false, false, Collections.<String>emptySet());
        }
        Map<String, Object> m = parseObject(jsonText);
        boolean configured = exists;
        if (m.containsKey("configured")) {
            configured = Boolean.TRUE.equals(m.get("configured"));
        }
        boolean compact = Boolean.TRUE.equals(m.get("compact_gaps"));
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
            return new Config(false, false, Collections.<String>emptySet());
        }
        try {
            return parseConfig(readUtf8(file), true);
        } catch (Exception ignored) {
            return new Config(false, false, Collections.<String>emptySet());
        }
    }

    private static InstallChecker checkerFromKeySet(final Set<String> installedKeys) {
        if (installedKeys == null) return null;
        return new InstallChecker() {
            @Override
            public boolean isBackupStillInstalled(String backupKey, String pkg, String cls, int itemId) {
                String exact = normKey(pkg, cls, itemId);
                if (installedKeys.contains(backupKey) || (!exact.isEmpty() && installedKeys.contains(exact))) {
                    return true;
                }
                String p = cleanToken(pkg);
                String c = cleanToken(cls);
                if (!p.isEmpty()) {
                    if (installedKeys.contains(p + "/*")) return true;
                    if (!"com.android.settings".equals(p)) {
                        String prefix = p + "/";
                        for (String ik : installedKeys) {
                            if (ik.startsWith(prefix)) return true;
                        }
                    }
                } else if (!c.isEmpty()) {
                    // Virtual @class/Radio backup matches com.hkmc.radio/com.hkmc.radio.RadioActivity
                    String lowC = c.toLowerCase(Locale.US);
                    for (String ik : installedKeys) {
                        String lowIk = ik.toLowerCase(Locale.US);
                        if (lowIk.endsWith("/" + lowC) || lowIk.endsWith("." + lowC)
                                || ("radio".equals(lowC) && lowIk.startsWith("com.hkmc.radio/"))) {
                            return true;
                        }
                    }
                }
                return false;
            }
        };
    }

    /**
     * Synchronises one profile's all_menu JSON against hidden-tile settings, backing up
     * hidden stock and mod tiles with their exact itemId/slot and restoring them when
     * unhidden (provided their activity is still installed when installedKeys != null).
     */
    public static SyncResult syncProfileMenu(String user, String menuJson, Config cfg,
            Set<String> installedKeys, Map<String, Object> allBackups) {
        return syncProfileMenu(user, menuJson, cfg, checkerFromKeySet(installedKeys),
                allBackups, null, null, false);
    }

    public static SyncResult syncProfileMenu(String user, String menuJson, Config cfg,
            InstallChecker installChecker, Map<String, Object> allBackups,
            List<ReconcileTile> reconcileTiles, Set<String> multiEntryPackages) {
        return syncProfileMenu(user, menuJson, cfg, installChecker,
                allBackups, reconcileTiles, multiEntryPackages, false);
    }

    @SuppressWarnings("unchecked")
    public static SyncResult syncProfileMenu(String user, String menuJson, Config cfg,
            InstallChecker installChecker, Map<String, Object> allBackups,
            List<ReconcileTile> reconcileTiles, Set<String> multiEntryPackages,
            boolean forceCompact) {
        List<String> logs = new ArrayList<String>();
        Map<String, Object> root = parseObject(menuJson);
        Object rawArr = root.get("menuItems");
        if (!(rawArr instanceof List)) return new SyncResult(null, false, logs);
        List<Object> arr = (List<Object>) rawArr;

        Map<String, Object> profiles = ensureMap(allBackups, "profiles");
        Map<String, Object> userBackup = ensureMap(profiles, user);

        boolean changed = false;
        boolean backupChanged = false;
        boolean newlyHidAny = false;

        // 1. Back up and clear any currently-placed tile that is marked hidden.
        //    If a backup already exists for this tile (e.g. HmgApi.syncSavedMenu temporarily
        //    re-inserted a hidden mod tile into a different empty slot on boot), preserve the
        //    user's original backed-up slot and do not treat it as a fresh hide event!
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
                newlyHidAny = true;
            }

            arr.set(k, emptySlotMap());
            changed = true;
            logs.add("saved menu (" + user + "): hid " + matchedKey + ", slot " + (k + 1));
        }

        // 2. Collect all non-empty keys currently placed on the grid
        Set<String> presentKeys = new LinkedHashSet<String>();
        Set<String> presentPackages = new LinkedHashSet<String>();
        Set<String> presentClasses = new LinkedHashSet<String>();
        Set<String> presentExpandedClasses = new LinkedHashSet<String>();
        Set<String> presentLowerTitles = new LinkedHashSet<String>();
        Set<Integer> presentItemIds = new LinkedHashSet<Integer>();
        for (int k = 0; k < arr.size(); k++) {
            if (!(arr.get(k) instanceof Map)) continue;
            Map<String, Object> it = (Map<String, Object>) arr.get(k);
            String pkg = str(it.get("packageName"));
            String cls = str(it.get("className"));
            int id = intVal(it.get("itemId"), -1);
            if (isEmptySlot(pkg, cls, id)) continue;
            presentKeys.add(normKey(pkg, cls, id));
            if (!pkg.isEmpty()) presentPackages.add(pkg);
            if (!cls.isEmpty()) {
                presentClasses.add(cls);
                presentExpandedClasses.add(expandClass(pkg, cls));
                if (pkg.isEmpty()) presentLowerTitles.add(cls.trim().toLowerCase(Locale.US));
            }
            if (id > 0 && id != 39) presentItemIds.add(Integer.valueOf(id));
        }

        // 3. Restore unhidden tiles from backup (pruning only those whose package was actually uninstalled)
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
            if (installChecker != null && !installChecker.isBackupStillInstalled(key, bPkg, bCls, bId)) {
                userBackup.remove(key);
                backupChanged = true;
                logs.add("saved menu (" + user + "): dropped backup for uninstalled " + key);
                continue;
            }
            String bExpanded = expandClass(bPkg, bCls);
            if (presentKeys.contains(key) || (!exactBakKey.isEmpty() && presentKeys.contains(exactBakKey))
                    || (!bCls.isEmpty() && !"Empty view".equals(bCls)
                        && (presentClasses.contains(bCls) || presentExpandedClasses.contains(bExpanded)))
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
            if (!bPkg.isEmpty()) presentPackages.add(bPkg);
            if (!bCls.isEmpty()) {
                presentClasses.add(bCls);
                presentExpandedClasses.add(bExpanded);
                if (bPkg.isEmpty()) presentLowerTitles.add(bCls.trim().toLowerCase(Locale.US));
            }
            if (bId > 0) presentItemIds.add(Integer.valueOf(bId));
            userBackup.remove(key);
            backupChanged = true;
            changed = true;
            logs.add("saved menu (" + user + "): restored " + key + " in slot " + (target + 1));
        }

        // 3b. When requested by Show All or explicit tile unhide, re-seat any verified catalog tiles
        //     that were orphaned from both all_menu and backup.json into available Empty view slots.
        if (reconcileTiles != null && !reconcileTiles.isEmpty()) {
            for (ReconcileTile rt : reconcileTiles) {
                if (rt == null || rt.packageName.isEmpty()) continue;
                if (cfg.hidden.contains(rt.key)
                        || matchingHiddenKey(cfg.hidden, rt.packageName, rt.className, rt.itemId) != null) {
                    continue;
                }
                if (installChecker != null
                        && !installChecker.isBackupStillInstalled(rt.key, rt.packageName, rt.className, rt.itemId)) {
                    continue;
                }
                if (isTileAlreadyPlaced(rt, presentKeys, presentPackages, presentClasses,
                        presentExpandedClasses, presentLowerTitles, presentItemIds, multiEntryPackages)) {
                    continue;
                }
                int target = firstEmptySlot(arr);
                if (target < 0) {
                    logs.add("saved menu (" + user + "): no empty slot to re-seat " + rt.key);
                    continue;
                }
                Map<String, Object> seated = new LinkedHashMap<String, Object>();
                seated.put("packageName", rt.packageName);
                seated.put("itemId", Long.valueOf(rt.itemId));
                seated.put("className", rt.className);
                arr.set(target, seated);
                presentKeys.add(rt.key);
                presentKeys.add(normKey(rt.packageName, rt.className, rt.itemId));
                presentPackages.add(rt.packageName);
                if (!rt.className.isEmpty()) {
                    presentClasses.add(rt.className);
                    presentExpandedClasses.add(expandClass(rt.packageName, rt.className));
                }
                if (rt.itemId > 0) presentItemIds.add(Integer.valueOf(rt.itemId));
                changed = true;
                logs.add("saved menu (" + user + "): re-seated missing " + rt.key + " in slot " + (target + 1));
            }
        }

        // 4. On-demand gap compaction: collapse empty spaces when explicitly requested via
        //    forceCompact (e.g. tapping Collapse Empty Spaces in Tile Manager), or when explicit
        //    compactGaps is set on a fresh hide event, without stripping multi-page spacing on passive reloads.
        if (forceCompact || (cfg.exists && cfg.compactGaps && newlyHidAny)) {
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

    public static boolean isPrimaryOwnerProfileName(String user) {
        String u = cleanToken(user);
        if (u.isEmpty() || u.startsWith("#") || "Guest".equalsIgnoreCase(u)) return false;
        return !isSecondaryDefaultProfileName(u);
    }

    @SuppressWarnings("unchecked")
    public static List<ProfileSlot> parseProfileSlots(String menuJson) {
        List<ProfileSlot> out = new ArrayList<ProfileSlot>();
        if (menuJson == null || menuJson.trim().isEmpty()) return out;
        try {
            Map<String, Object> root = parseObject(menuJson);
            Object rawArr = root.get("menuItems");
            if (!(rawArr instanceof List)) return out;
            List<Object> arr = (List<Object>) rawArr;
            for (int i = 0; i < arr.size(); i++) {
                Object o = arr.get(i);
                if (!(o instanceof Map)) {
                    out.add(new ProfileSlot(i, true, EMPTY_SLOT_TOKEN, "Empty view", "Empty view", 39));
                    continue;
                }
                Map<String, Object> it = (Map<String, Object>) o;
                String pkg = cleanToken(it.get("packageName"));
                String cls = cleanToken(it.get("className"));
                int id = intVal(it.get("itemId"), -1);
                if (isEmptySlot(pkg, cls, id)) {
                    out.add(new ProfileSlot(i, true, EMPTY_SLOT_TOKEN, "Empty view", "Empty view", 39));
                } else {
                    out.add(new ProfileSlot(i, false, normKey(pkg, cls, id), pkg, cls, id));
                }
            }
        } catch (Throwable ignored) {}
        return out;
    }

    /**
     * Losslessly reorders a driver profile's all_menu JSON according to desiredSlotTokens,
     * preserving exact Map entries (packageName, itemId, className, including @class/*, @item/*,
     * and 9000+ HMG-API mod IDs), retaining any unmentioned placed tiles so zero tiles can ever
     * be lost, respecting "@empty" spacers when !compactGaps within grid capacity, and padding
     * trailing cells to the profile's exact menuItems slot count with "Empty view".
     */
    @SuppressWarnings("unchecked")
    public static SyncResult reorderProfileMenu(String user, String menuJson,
                                                List<String> desiredSlotTokens, boolean compactGaps) {
        List<String> logs = new ArrayList<String>();
        if (menuJson == null || menuJson.trim().isEmpty() || desiredSlotTokens == null) {
            return new SyncResult(null, false, logs);
        }
        Map<String, Object> root = parseObject(menuJson);
        Object rawArr = root.get("menuItems");
        if (!(rawArr instanceof List)) return new SyncResult(null, false, logs);
        List<Object> arr = (List<Object>) rawArr;
        if (arr.isEmpty()) return new SyncResult(null, false, logs);

        List<Map<String, Object>> remainingPlaced = new ArrayList<Map<String, Object>>();
        Map<String, Integer> pkgCounts = new LinkedHashMap<String, Integer>();
        for (int i = 0; i < arr.size(); i++) {
            if (!(arr.get(i) instanceof Map)) continue;
            Map<String, Object> it = (Map<String, Object>) arr.get(i);
            String pkg = cleanToken(it.get("packageName"));
            String cls = cleanToken(it.get("className"));
            int id = intVal(it.get("itemId"), -1);
            if (isEmptySlot(pkg, cls, id)) continue;
            remainingPlaced.add(new LinkedHashMap<String, Object>(it));
            if (!pkg.isEmpty()) {
                Integer prev = pkgCounts.get(pkg);
                pkgCounts.put(pkg, Integer.valueOf(prev == null ? 1 : prev.intValue() + 1));
            }
        }

        Set<String> multiPkgs = new LinkedHashSet<String>();
        multiPkgs.add("com.android.settings");
        for (Map.Entry<String, Integer> pe : pkgCounts.entrySet()) {
            if (pe.getValue().intValue() > 1) multiPkgs.add(pe.getKey());
        }

        int totalSlots = Math.max(arr.size(), remainingPlaced.size());
        int placedCount = remainingPlaced.size();
        List<Object> newArr = new ArrayList<Object>(totalSlots);

        for (int i = 0; i < desiredSlotTokens.size(); i++) {
            String rawTok = desiredSlotTokens.get(i);
            if (rawTok == null) continue;
            String trimmed = rawTok.trim();
            if (EMPTY_SLOT_TOKEN.equalsIgnoreCase(trimmed)) {
                if (!compactGaps && newArr.size() + remainingPlaced.size() < totalSlots) {
                    newArr.add(emptySlotMap());
                }
                continue;
            }
            String desiredKey = normKeyString(trimmed);
            if (desiredKey.isEmpty()) continue;
            int matchIdx = findMatchingPlacedIndex(remainingPlaced, desiredKey, multiPkgs);
            if (matchIdx >= 0) {
                newArr.add(remainingPlaced.remove(matchIdx));
            }
        }

        for (int i = 0; i < remainingPlaced.size(); i++) {
            newArr.add(remainingPlaced.get(i));
        }
        while (newArr.size() < totalSlots) {
            newArr.add(emptySlotMap());
        }

        if (slotsIdentical(arr, newArr)) {
            return new SyncResult(null, false, logs);
        }

        arr.clear();
        arr.addAll(newArr);
        logs.add("saved menu (" + user + "): applied custom tile order (" + placedCount + " tiles)");
        return new SyncResult(writeCompact(root), false, logs);
    }

    private static int findMatchingPlacedIndex(List<Map<String, Object>> candidates,
                                               String desiredKey, Set<String> multiPkgs) {
        // Pass 1: exact normKey match
        for (int i = 0; i < candidates.size(); i++) {
            Map<String, Object> it = candidates.get(i);
            String exact = normKey(str(it.get("packageName")), str(it.get("className")),
                    intVal(it.get("itemId"), -1));
            if (desiredKey.equals(exact)) return i;
        }
        // Pass 2: cross-representation match (@class/Radio, @item/N, or single-entry pkg/*)
        int slash = desiredKey.indexOf('/');
        String dLeft = slash > 0 ? desiredKey.substring(0, slash) : "";
        String dRight = slash > 0 && slash + 1 < desiredKey.length() ? desiredKey.substring(slash + 1) : "";
        for (int i = 0; i < candidates.size(); i++) {
            Map<String, Object> it = candidates.get(i);
            String pkg = cleanToken(it.get("packageName"));
            String cls = cleanToken(it.get("className"));
            int id = intVal(it.get("itemId"), -1);
            if ("@item".equals(dLeft)) {
                int wantId = intVal(dRight, -1);
                if (wantId > 0 && id == wantId) return i;
                continue;
            }
            if ("@class".equals(dLeft)) {
                if (!cls.isEmpty() && (dRight.equalsIgnoreCase(cls)
                        || dRight.equalsIgnoreCase(expandClass(pkg, cls)))) {
                    return i;
                }
                if ("Radio".equalsIgnoreCase(dRight)
                        && ("com.hkmc.radio".equals(pkg) || pkg.startsWith("com.hkmc.radio."))) {
                    return i;
                }
                continue;
            }
            if (pkg.isEmpty() && !cls.isEmpty()) {
                if ("Radio".equalsIgnoreCase(cls)
                        && ("com.hkmc.radio".equals(dLeft) || dLeft.startsWith("com.hkmc.radio."))) {
                    return i;
                }
                String lowCls = cls.toLowerCase(Locale.US);
                String lowRight = dRight.toLowerCase(Locale.US);
                if (lowRight.equals(lowCls) || lowRight.endsWith("." + lowCls)) {
                    return i;
                }
                continue;
            }
            if (!pkg.isEmpty() && pkg.equals(dLeft)) {
                if (!multiPkgs.contains(pkg)) return i;
                if ("com.android.settings/com.android.settings.Settings".equals(desiredKey)
                        && (cls.isEmpty() || "*".equals(cls))) {
                    return i;
                }
            }
        }
        return -1;
    }

    @SuppressWarnings("unchecked")
    private static boolean slotsIdentical(List<Object> a, List<Object> b) {
        if (a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++) {
            boolean aEmpty = slotEmpty(a.get(i));
            boolean bEmpty = slotEmpty(b.get(i));
            if (aEmpty != bEmpty) return false;
            if (aEmpty) continue;
            Map<String, Object> ma = (Map<String, Object>) a.get(i);
            Map<String, Object> mb = (Map<String, Object>) b.get(i);
            if (!cleanToken(ma.get("packageName")).equals(cleanToken(mb.get("packageName")))) return false;
            if (!cleanToken(ma.get("className")).equals(cleanToken(mb.get("className")))) return false;
            if (intVal(ma.get("itemId"), -1) != intVal(mb.get("itemId"), -1)) return false;
        }
        return true;
    }

    private static boolean isTileAlreadyPlaced(ReconcileTile rt, Set<String> presentKeys,
                                               Set<String> presentPackages, Set<String> presentClasses,
                                               Set<String> presentExpandedClasses, Set<String> presentLowerTitles,
                                               Set<Integer> presentItemIds, Set<String> multiEntryPackages) {
        if (presentKeys.contains(rt.key)) return true;
        String exact = normKey(rt.packageName, rt.className, rt.itemId);
        if (!exact.isEmpty() && presentKeys.contains(exact)) return true;
        if (!rt.className.isEmpty()) {
            String exp = expandClass(rt.packageName, rt.className);
            if (presentClasses.contains(rt.className) || presentExpandedClasses.contains(exp)) {
                return true;
            }
        }
        if (!rt.title.isEmpty() && presentLowerTitles.contains(rt.title.toLowerCase(Locale.US))) {
            return true;
        }
        if (("com.hkmc.radio".equals(rt.packageName) || rt.packageName.startsWith("com.hkmc.radio."))
                && presentLowerTitles.contains("radio")) {
            return true;
        }
        boolean isMulti = "com.android.settings".equals(rt.packageName)
                || (multiEntryPackages != null && multiEntryPackages.contains(rt.packageName));
        if (!isMulti) {
            if (presentPackages.contains(rt.packageName) || presentKeys.contains(rt.packageName + "/*")) {
                return true;
            }
        } else if ("com.android.settings/com.android.settings.Settings".equals(rt.key)
                && presentKeys.contains("com.android.settings/*")) {
            return true;
        }
        return false;
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
