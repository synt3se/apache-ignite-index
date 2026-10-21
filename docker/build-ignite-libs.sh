#!/usr/bin/env bash
# Собирает комплект jar-ов для узла Ignite из docker/docker-compose.yml (каталог ignite-libs
# монтируется в libs/ext): наши модули и их runtime-зависимости без самого Ignite.
#
# По умолчанию пишет в target/ignite-libs и печатает, что добавится или заменится в
# docker/ignite-libs. Ничего не удаляет. В целевой каталог копирует только с --install.
#
#   docker/build-ignite-libs.sh                       # собрать и сравнить
#   docker/build-ignite-libs.sh --install docker/ignite-libs
#   docker/build-ignite-libs.sh --out /tmp/libs --compare docker/ignite-libs
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OUT="$ROOT/target/ignite-libs"
COMPARE="$ROOT/docker/ignite-libs"
INSTALL=""

while [[ $# -gt 0 ]]; do
  case "$1" in
    --out) OUT="$2"; shift 2 ;;
    --compare) COMPARE="$2"; shift 2 ;;
    --install) INSTALL="$2"; COMPARE="$2"; shift 2 ;;
    -h|--help) sed -n '2,12p' "$0"; exit 0 ;;
    *) echo "unknown argument: $1" >&2; exit 2 ;;
  esac
done

mkdir -p "$OUT"
# Maven разрешает относительный outputDirectory от каталога модуля, а не от текущего.
OUT="$(cd "$OUT" && pwd)"
echo "[build-ignite-libs] building vector-index-node and its modules (tests skipped)"
mvn -B -q -f "$ROOT/pom.xml" -pl vector-index-node -am -DskipTests install
mvn -B -q -f "$ROOT/pom.xml" -pl vector-index-node dependency:copy-dependencies \
    -DincludeScope=runtime -DexcludeGroupIds=org.apache.ignite -DoutputDirectory="$OUT"
cp "$ROOT"/vector-index-node/target/vector-index-node-*.jar "$OUT"/

echo "[build-ignite-libs] built into $OUT"
if [[ -d "$COMPARE" ]]; then
  echo "[build-ignite-libs] compared with $COMPARE:"
  for jar in "$OUT"/*.jar; do
    name="$(basename "$jar")"
    if [[ ! -e "$COMPARE/$name" ]]; then
      echo "  + $name (new)"
    elif ! cmp -s "$jar" "$COMPARE/$name"; then
      echo "  ~ $name (will be replaced)"
    fi
  done
  for jar in "$COMPARE"/*.jar; do
    [[ -e "$jar" ]] || continue
    name="$(basename "$jar")"
    [[ -e "$OUT/$name" ]] || echo "  = $name (not produced by this build, kept)"
  done
fi

if [[ -n "$INSTALL" ]]; then
  mkdir -p "$INSTALL"
  cp "$OUT"/*.jar "$INSTALL"/
  echo "[build-ignite-libs] copied into $INSTALL (nothing removed)"
fi
