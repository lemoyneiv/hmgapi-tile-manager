#!/bin/sh
# Off-car engine & package structure test for hmgapi-tile-manager using the HMG-API Mod SDK.
set -e
M=$(cd "$(dirname "$0")" && pwd)
B=${HMGAPI_MOD_BUILD:?set HMGAPI_MOD_BUILD to the built mod (the out dir of bin/build-mod.sh)}
T=$(mktemp -d); trap 'rm -rf "$T"' EXIT
R=$T/root
. "${HMGAPI_SDK:?set HMGAPI_SDK to the unpacked mod SDK}/test/lib.sh"

mkdir -p "$R/system" "$R/data/hmgapi/settings"
printf 'ro.board.platform=tcc803x\n' > "$R/props.txt"

check "built: hooks.jar exists" yes "$(test -f "$B/hooks.jar" && echo yes || echo no)"
check "built: TileManager.apk exists" yes "$(test -f "$B/app/TileManager.apk" && echo yes || echo no)"

HOOK_STRINGS=$(unzip -p "$B/hooks.jar" classes.dex | strings)
has "hooks.jar contains TileModEntry" "Ldev/lemoyne/tiles/hook/TileModEntry;" "$HOOK_STRINGS"
has "hooks.jar contains TileStore" "Ldev/lemoyne/tiles/hook/TileStore;" "$HOOK_STRINGS"
has "hooks.jar contains FilterableMenuList" "Ldev/lemoyne/tiles/hook/TileStore\$FilterableMenuList;" "$HOOK_STRINGS"
has "hooks.jar contains EntryInspector" "Ldev/lemoyne/tiles/hook/TileStore\$EntryInspector;" "$HOOK_STRINGS"
has "hooks.jar contains InstallChecker" "Ldev/lemoyne/tiles/hook/TileStore\$InstallChecker;" "$HOOK_STRINGS"
has "hooks.jar contains ReconcileTile" "Ldev/lemoyne/tiles/hook/TileStore\$ReconcileTile;" "$HOOK_STRINGS"
has "hooks.jar contains ProfileSlot" "Ldev/lemoyne/tiles/hook/TileStore\$ProfileSlot;" "$HOOK_STRINGS"
has "hooks.jar contains reorderProfileMenu" "reorderProfileMenu" "$HOOK_STRINGS"
has "hooks.jar contains ACTION_OPEN_STOCK_EDITOR" "dev.lemoyne.tiles.ACTION_OPEN_STOCK_EDITOR" "$HOOK_STRINGS"
has "hooks.jar contains stockPseudoTargetPackages" "stockPseudoTargetPackages" "$HOOK_STRINGS"

APK_STRINGS=$(unzip -p "$B/app/TileManager.apk" classes.dex | strings)
has "apk contains MainActivity" "Ldev/lemoyne/tiles/MainActivity;" "$APK_STRINGS"
has "apk contains TileCatalogProvider" "Ldev/lemoyne/tiles/TileCatalogProvider;" "$APK_STRINGS"
has "apk contains TileTheme" "Ldev/lemoyne/tiles/TileTheme;" "$APK_STRINGS"
has "apk contains TileIcons" "Ldev/lemoyne/tiles/TileIcons;" "$APK_STRINGS"
has "apk contains TileWidgets" "Ldev/lemoyne/tiles/TileWidgets;" "$APK_STRINGS"
has "apk contains SystemIconResolver" "Ldev/lemoyne/tiles/SystemIconResolver;" "$APK_STRINGS"
has "apk bundles HmgApi client" "Lhmgapi/client/HmgApi;" "$APK_STRINGS"
has "apk contains refreshCatalog" "refreshCatalog" "$APK_STRINGS"
has "apk contains force_restore_all" "force_restore_all" "$APK_STRINGS"
has "apk contains ACTION_OPEN_STOCK_EDITOR" "dev.lemoyne.tiles.ACTION_OPEN_STOCK_EDITOR" "$APK_STRINGS"
has "apk contains Edit Home Icons button" "Edit Home Icons" "$APK_STRINGS"
has "apk contains Collapse Empty Spaces button" "Collapse Empty Spaces" "$APK_STRINGS"
has "apk contains author credit" "by lemoyneiv" "$APK_STRINGS"
has "apk contains v1.0.7 header" "v1.0.7" "$APK_STRINGS"

HOOK_SCHEMA=$(sed -n 's/.*CATALOG_SCHEMA *= *\([0-9][0-9]*\).*/\1/p' "$M/src/dev/lemoyne/tiles/hook/TileStore.java" | head -n 1)
APP_SCHEMA=$(sed -n 's/.*CATALOG_SCHEMA *= *\([0-9][0-9]*\).*/\1/p' "$M/app/java/dev/lemoyne/tiles/TileCatalogProvider.java" | head -n 1)
check "hook and provider CATALOG_SCHEMA match" "$HOOK_SCHEMA" "$APP_SCHEMA"

for ic in icon.png icon_night.png icon_disabled.png icon_disabled_night.png; do
    check "launcher tile $ic exists" yes "$(test -f "$M/icons/$ic" && echo yes || echo no)"
done
check "open.sh exists" yes "$(test -f "$M/open.sh" && echo yes || echo no)"
check "hmgapi-tile-manager attributed solely to lemoyneiv" "lemoyneiv" \
    "$(python3 -c 'import json,sys; print(",".join(json.load(open(sys.argv[1]))["authors"]))' "$M/mod.json")"

install_mod "$B"
cp -r "$M/icons" "$R/data/hmgapi/mods/hmgapi-tile-manager/"

eng plan
check "engine plan succeeds" 0 "$rc"
has "plan lists TileManager.apk" "dev.lemoyne.tiles (app/TileManager.apk)" "$out"
has "plan lists hooks.jar" "hooks" "$out"
check "plan has zero problems" no "$(echo "$out" | grep -q '^problem ' && echo yes || echo no)"

eng apply
check "engine apply succeeds" 0 "$rc"
has "mod published as on" "1 of 1 mods on" "$out"
has "mod in public state.json" '"hmgapi-tile-manager"' "$(cat "$R/data/hmgapi/public/state.json")"
has "default hidden_keys in public state.json" '"hidden_keys": ""' "$(cat "$R/data/hmgapi/public/state.json")"

printf '{"compact_gaps":false,"hidden_keys":"com.hkmc.radio/com.hkmc.radio.RadioActivity"}\n' \
    > "$R/data/hmgapi/settings/hmgapi-tile-manager.json"
eng apply
check "setting override applies cleanly" 0 "$rc"
has "public state reflects hidden_keys" 'com.hkmc.radio/com.hkmc.radio.RadioActivity' "$(cat "$R/data/hmgapi/public/state.json")"

eng remove hmgapi-tile-manager
check "engine remove succeeds" 0 "$rc"
eng apply
check "apply after remove succeeds" 0 "$rc"
exit "$fail"
