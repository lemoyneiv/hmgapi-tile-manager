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
