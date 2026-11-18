#!/bin/sh
# Host JVM unit test for TileStore (backup/restore, gap compaction, uninstalled pruning, JSON codec).
set -e
D=$(cd "$(dirname "$0")" && pwd)
M=$(cd "$D/../.." && pwd)
T=$(mktemp -d); trap 'rm -rf "$T"' EXIT

javac -source 8 -target 8 -encoding UTF-8 -Xlint:-options \
    -d "$T" \
    "$M/src/dev/lemoyne/tiles/hook/TileStore.java" \
    "$D/TileStoreTest.java"

java -cp "$T" dev.lemoyne.tiles.hook.TileStoreTest

AJAR="${ANDROID_HOME:-$HOME/Android}/platforms/android-19/android.jar"
if [ -f "$AJAR" ]; then
    javac -source 8 -target 8 -encoding UTF-8 -Xlint:-options \
        -cp "$AJAR" -d "$T" \
        "$M/app/java/dev/lemoyne/tiles/TileTheme.java" \
        "$M/app/java/dev/lemoyne/tiles/TileIcons.java" \
        "$M/app/java/dev/lemoyne/tiles/TileWidgets.java" \
        "$M/app/java/dev/lemoyne/tiles/SystemIconResolver.java" \
        "$D/SystemIconResolverTest.java"
    java -cp "$T:$AJAR" dev.lemoyne.tiles.SystemIconResolverTest
fi

HOOK_SCHEMA=$(sed -n 's/.*CATALOG_SCHEMA *= *\([0-9][0-9]*\).*/\1/p' "$M/src/dev/lemoyne/tiles/hook/TileStore.java" | head -n 1)
APP_SCHEMA=$(sed -n 's/.*CATALOG_SCHEMA *= *\([0-9][0-9]*\).*/\1/p' "$M/app/java/dev/lemoyne/tiles/TileCatalogProvider.java" | head -n 1)
if [ -z "$HOOK_SCHEMA" ] || [ "$HOOK_SCHEMA" != "$APP_SCHEMA" ]; then
    echo "FAIL: CATALOG_SCHEMA mismatch: TileStore=$HOOK_SCHEMA vs TileCatalogProvider=$APP_SCHEMA" >&2
    exit 1
fi
echo "Schema parity check passed: CATALOG_SCHEMA=$HOOK_SCHEMA"
