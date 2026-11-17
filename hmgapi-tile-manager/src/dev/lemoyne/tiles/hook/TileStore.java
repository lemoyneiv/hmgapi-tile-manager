package dev.lemoyne.tiles.hook;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Pure-JVM Home Screen tile visibility configuration, minimal JSON codec, and saved-menu
 * grid synchroniser. Free of Android framework and internal HMG-API engine imports so
 * tools/unit/test.sh exercises all backup/restore, compaction, and multi-profile scenarios
 * directly on the host JVM.
 */
public final class TileStore {
    private TileStore() {}

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

    public static boolean isEmptySlot(String pkg, String cls) {
        return pkg == null || pkg.isEmpty() || "Empty view".equals(pkg) || "Empty view".equals(cls);
    }

    public static String normKey(String pkg, String cls) {
        String p = pkg == null ? "" : pkg.trim();
        String c = cls == null ? "" : cls.trim();
        if (!p.isEmpty() && c.startsWith(".")) c = p + c;
        if (p.isEmpty() || c.isEmpty()) return "";
        return p + "/" + c;
    }

    public static String normKeyString(String raw) {
        if (raw == null) return "";
        String s = raw.trim();
        int slash = s.indexOf('/');
        if (slash <= 0 || slash + 1 >= s.length()) return "";
        return normKey(s.substring(0, slash), s.substring(slash + 1));
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

        // 1. Back up and clear any currently-placed tile that is marked hidden
        for (int k = 0; k < arr.size(); k++) {
            if (!(arr.get(k) instanceof Map)) continue;
            Map<String, Object> it = (Map<String, Object>) arr.get(k);
            String pkg = str(it.get("packageName"));
            String cls = str(it.get("className"));
            if (isEmptySlot(pkg, cls)) continue;
            int id = intVal(it.get("itemId"), -1);
            String key = normKey(pkg, cls);
            if (key.isEmpty() || !cfg.hidden.contains(key)) continue;

            Map<String, Object> bak = new LinkedHashMap<String, Object>();
            bak.put("packageName", pkg);
            bak.put("itemId", Long.valueOf(id));
            bak.put("className", cls);
            bak.put("slot", Long.valueOf(k));
            userBackup.put(key, bak);
            backupChanged = true;

            arr.set(k, emptySlotMap());
            changed = true;
            logs.add("saved menu (" + user + "): hid " + key + ", slot " + (k + 1));
        }

        // 2. Collect all non-empty keys currently placed on the grid
        Set<String> presentKeys = new LinkedHashSet<String>();
        Set<String> presentClasses = new LinkedHashSet<String>();
        for (int k = 0; k < arr.size(); k++) {
            if (!(arr.get(k) instanceof Map)) continue;
            Map<String, Object> it = (Map<String, Object>) arr.get(k);
            String pkg = str(it.get("packageName"));
            String cls = str(it.get("className"));
            if (isEmptySlot(pkg, cls)) continue;
            presentKeys.add(normKey(pkg, cls));
            presentClasses.add(cls);
        }

        // 3. Restore unhidden tiles from backup (pruning any whose package was uninstalled)
        for (String key : new ArrayList<String>(userBackup.keySet())) {
            if (cfg.hidden.contains(key)) continue;
            Object rawBak = userBackup.get(key);
            if (!(rawBak instanceof Map)) {
                userBackup.remove(key);
                backupChanged = true;
                continue;
            }
            if (installedKeys != null && !installedKeys.contains(key)) {
                userBackup.remove(key);
                backupChanged = true;
                logs.add("saved menu (" + user + "): dropped backup for uninstalled " + key);
                continue;
            }
            Map<String, Object> bak = (Map<String, Object>) rawBak;
            String bPkg = str(bak.get("packageName"));
            String bCls = str(bak.get("className"));
            if (presentKeys.contains(key) || (!bCls.isEmpty() && presentClasses.contains(bCls))) {
                userBackup.remove(key);
                backupChanged = true;
                continue;
            }
            int bId = intVal(bak.get("itemId"), 0);
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
            presentKeys.add(key);
            presentClasses.add(bCls);
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
        return isEmptySlot(str(m.get("packageName")), str(m.get("className")));
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
