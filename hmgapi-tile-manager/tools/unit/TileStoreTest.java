package dev.lemoyne.tiles.hook;

import java.io.File;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Pure-JVM unit tests for TileStore:
 *  - key normalisation (.Activity shorthand vs full className)
 *  - JSON parser/writer escaping & config parsing (array "hidden" + CSV "hidden_keys")
 *  - hiding & restoring both stock (itemId 1..38) and mod (itemId >= 9000) tiles at exact slots
 *  - pruning uninstalled mod/app backups on restore so phantom tiles never reappear
 *  - gap compaction (compact_gaps=true vs compact_gaps=false)
 *  - multi-profile (Driver 1 / Driver 2) backup isolation & idempotence
 */
public final class TileStoreTest {
    private static int checks = 0;

    private static void check(String label, Object expected, Object actual) {
        checks++;
        boolean eq = (expected == null) ? (actual == null) : expected.equals(actual);
        if (!eq) {
            throw new AssertionError("FAIL [" + label + "]: expected <" + expected + ">, got <" + actual + ">");
        }
    }

    private static void checkTrue(String label, boolean cond) {
        check(label, Boolean.TRUE, Boolean.valueOf(cond));
    }

    private static Set<String> setOf(String... items) {
        return new LinkedHashSet<String>(Arrays.asList(items));
    }

    @SuppressWarnings("unchecked")
    private static String slotPkg(String menuJson, int slot) {
        Map<String, Object> m = TileStore.parseObject(menuJson);
        List<Object> arr = (List<Object>) m.get("menuItems");
        Map<String, Object> it = (Map<String, Object>) arr.get(slot);
        return TileStore.str(it.get("packageName"));
    }

    @SuppressWarnings("unchecked")
    private static int slotItemId(String menuJson, int slot) {
        Map<String, Object> m = TileStore.parseObject(menuJson);
        List<Object> arr = (List<Object>) m.get("menuItems");
        Map<String, Object> it = (Map<String, Object>) arr.get(slot);
        return TileStore.intVal(it.get("itemId"), -999);
    }

    private static String sampleGrid() {
        return "{\"menuItems\":["
                + "{\"packageName\":\"com.hkmc.ev\",\"itemId\":3,\"className\":\"com.hkmc.ev.EvActivity\"},"
                + "{\"packageName\":\"com.hkmc.radio\",\"itemId\":8,\"className\":\".RadioActivity\"},"
                + "{\"packageName\":\"dev.lemoyne.comfort\",\"itemId\":9001,\"className\":\"dev.lemoyne.comfort.MainActivity\"},"
                + "{\"packageName\":\"hmgapi.app\",\"itemId\":9000,\"className\":\"hmgapi.app.MainActivity\"},"
                + "{\"packageName\":\"Empty view\",\"itemId\":39,\"className\":\"Empty view\"}"
                + "]}";
    }

    public static void main(String[] args) throws Exception {
        // 1. Key normalisation
        check("normKey shorthand class", "com.hkmc.radio/com.hkmc.radio.RadioActivity",
                TileStore.normKey("com.hkmc.radio", ".RadioActivity"));
        check("normKey full class", "dev.lemoyne.comfort/dev.lemoyne.comfort.MainActivity",
                TileStore.normKey("dev.lemoyne.comfort", "dev.lemoyne.comfort.MainActivity"));
        check("normKeyString shorthand", "dev.lemoyne.tiles/dev.lemoyne.tiles.MainActivity",
                TileStore.normKeyString(" dev.lemoyne.tiles/.MainActivity "));
        check("normKeyString malformed", "", TileStore.normKeyString("no-slash"));

        // 2. Config JSON & CSV parsing + signature determinism
        TileStore.Config c1 = TileStore.parseConfig(
                "{\"compact_gaps\":false,\"hidden\":[\"b/.Two\",\"a/a.One\"]}", true);
        check("parseConfig exists", Boolean.TRUE, Boolean.valueOf(c1.exists));
        check("parseConfig compactGaps=false", Boolean.FALSE, Boolean.valueOf(c1.compactGaps));
        check("parseConfig expands shorthand", Boolean.TRUE, Boolean.valueOf(c1.hidden.contains("b/b.Two")));

        TileStore.Config c2 = TileStore.parseConfig(
                "{\"configured\":true,\"compact_gaps\":false,\"hidden_keys\":\"a/a.One, b/b.Two\"}", false);
        check("CSV config matches JSON array signature", c1.signature(true), c2.signature(true));
        checkTrue("mod disabled changes signature", !c1.signature(true).equals(c1.signature(false)));

        // 3. Hide stock + mod tile WITH gap compaction
        Map<String, Object> backups = new LinkedHashMap<String, Object>();
        Set<String> installed = setOf(
                "com.hkmc.ev/com.hkmc.ev.EvActivity",
                "com.hkmc.radio/com.hkmc.radio.RadioActivity",
                "dev.lemoyne.comfort/dev.lemoyne.comfort.MainActivity",
                "hmgapi.app/hmgapi.app.MainActivity");

        TileStore.Config hideTwo = new TileStore.Config(true, true, setOf(
                "com.hkmc.radio/com.hkmc.radio.RadioActivity",
                "dev.lemoyne.comfort/dev.lemoyne.comfort.MainActivity"));
        TileStore.SyncResult r1 = TileStore.syncProfileMenu("Driver 1", sampleGrid(), hideTwo, installed, backups);
        checkTrue("hideTwo updates menu", r1.updatedMenuJson != null);
        checkTrue("hideTwo updates backup", r1.backupChanged);
        // Slots 0 and 1 should now hold EV and HMG-API (compacted left!), slots 2..4 empty
        check("compacted slot 0 is EV", "com.hkmc.ev", slotPkg(r1.updatedMenuJson, 0));
        check("compacted slot 1 is HMG-API", "hmgapi.app", slotPkg(r1.updatedMenuJson, 1));
        check("compacted slot 2 is Empty", "Empty view", slotPkg(r1.updatedMenuJson, 2));
        check("compacted slot 3 is Empty", "Empty view", slotPkg(r1.updatedMenuJson, 3));
        checkTrue("all_menu JSON keeps integer itemId without .0", !r1.updatedMenuJson.contains(".0"));

        // 4. Idempotence when nothing changes on second pass
        TileStore.SyncResult rIdem = TileStore.syncProfileMenu("Driver 1", r1.updatedMenuJson, hideTwo, installed, backups);
        check("idempotent second pass returns null menu diff", null, rIdem.updatedMenuJson);
        check("idempotent second pass leaves backup untouched", Boolean.FALSE, Boolean.valueOf(rIdem.backupChanged));

        // 5. Restore when one tile was uninstalled while hidden (prunes uninstalled, restores installed with exact itemId)
        Set<String> afterUninstall = setOf(
                "com.hkmc.ev/com.hkmc.ev.EvActivity",
                "com.hkmc.radio/com.hkmc.radio.RadioActivity",
                "hmgapi.app/hmgapi.app.MainActivity"); // comfort was uninstalled!
        TileStore.Config showAll = new TileStore.Config(true, true, Collections.<String>emptySet());
        TileStore.SyncResult rRestore = TileStore.syncProfileMenu(
                "Driver 1", r1.updatedMenuJson, showAll, afterUninstall, backups);
        checkTrue("restore updates menu", rRestore.updatedMenuJson != null);
        check("Radio restored into first available slot (slot 2)", "com.hkmc.radio", slotPkg(rRestore.updatedMenuJson, 2));
        check("Radio preserves original stock itemId 8", Integer.valueOf(8), Integer.valueOf(slotItemId(rRestore.updatedMenuJson, 2)));
        check("Uninstalled comfort mod NOT restored as phantom tile", "Empty view", slotPkg(rRestore.updatedMenuJson, 3));
        Map<?, ?> profilesAfter = (Map<?, ?>) backups.get("profiles");
        check("Empty profile backup cleaned up", Boolean.TRUE, Boolean.valueOf(profilesAfter.isEmpty()));

        // 6. Hide WITHOUT gap compaction preserves exact original slot on unhide
        TileStore.Config hideNoCompact = new TileStore.Config(true, false, setOf(
                "com.hkmc.radio/com.hkmc.radio.RadioActivity"));
        TileStore.SyncResult rNoComp = TileStore.syncProfileMenu("Driver 2", sampleGrid(), hideNoCompact, installed, backups);
        check("slot 1 becomes Empty view in place", "Empty view", slotPkg(rNoComp.updatedMenuJson, 1));
        check("slot 2 stays Comfort without shifting", "dev.lemoyne.comfort", slotPkg(rNoComp.updatedMenuJson, 2));

        TileStore.Config unhideNoCompact = new TileStore.Config(true, false, Collections.<String>emptySet());
        TileStore.SyncResult rRestExact = TileStore.syncProfileMenu(
                "Driver 2", rNoComp.updatedMenuJson, unhideNoCompact, installed, backups);
        check("Radio returns to exact original slot 1", "com.hkmc.radio", slotPkg(rRestExact.updatedMenuJson, 1));
        check("Radio keeps itemId 8", Integer.valueOf(8), Integer.valueOf(slotItemId(rRestExact.updatedMenuJson, 1)));

        // 7. Atomic file round-trip & string escaping
        File tmp = File.createTempFile("tilestore-test", ".json");
        tmp.deleteOnExit();
        Map<String, Object> special = new LinkedHashMap<String, Object>();
        special.put("quote", "Kia \"EV6\"\nLine2\tTab\\Back");
        special.put("num", Long.valueOf(9001));
        TileStore.writeAtomic(tmp, TileStore.writeCompact(special));
        Map<String, Object> parsed = TileStore.parseObject(TileStore.readUtf8(tmp));
        check("JSON escape round-trip", "Kia \"EV6\"\nLine2\tTab\\Back", parsed.get("quote"));
        check("JSON number round-trip", Long.valueOf(9001), parsed.get("num"));
        tmp.delete();

        System.out.println("TileStoreTest: " + checks + " checks passed");
    }
}
