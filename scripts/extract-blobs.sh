#!/usr/bin/env bash
# extract-blobs.sh — fetch the proprietary sensor libraries that Mogluco cannot ship.
#
# Mogluco reuses Juggluco's engine, which loads a few closed-source .so blobs at
# runtime (Libre calibration + Libre 3 crypto / activation libraries). They are
# not redistributable under the GPL, so this repository does not contain them.
# Instead — exactly as upstream Juggluco's own build instructions describe — they
# are extracted from official Juggluco APKs (https://www.juggluco.nl) and dropped
# into the jniLibs directories the build expects.
#
# The build needs four libraries (see core/build.gradle):
#   Common/src/main/jniLibs/<abi>/libcalibrat2.so         Libre calibration
#   Common/src/libre3/jniLibs/<abi>/libinit.so            Libre 3 loader
#   Common/src/libre3/jniLibs/<abi>/liblibre3extension.so Libre 3 crypto
#   Common/src/libre3/jniLibs/arm64-v8a/libcrl_dp.so      Libre 3 NFC activation
#
# The first three come from the current Juggluco APK. libcrl_dp.so was removed
# from Juggluco after v10.6.x (its function moved inside liblibre3extension.so),
# but Mogluco's vendored engine still dlopen()s the standalone file, so it is
# pulled from a pinned older APK that still ships it.
#
# Usage:
#   scripts/extract-blobs.sh              download the pinned Juggluco APK(s) and extract
#   scripts/extract-blobs.sh <path.apk>   extract from an already-downloaded Juggluco APK
#                                         (a multi-arch APK <=10.6.x supplies all four libs)
#   scripts/extract-blobs.sh --force      re-extract even if all blobs are already present
#
# Environment:
#   JUGGLUCO_VERSION       Juggluco APK for the three core libs (default below)
#   JUGGLUCO_CRL_VERSION   Juggluco APK that still bundles libcrl_dp.so (default below)
set -euo pipefail

VERSION="${JUGGLUCO_VERSION:-10.9.7}"
CRL_VERSION="${JUGGLUCO_CRL_VERSION:-10.6.1}"
sf() { echo "https://sourceforge.net/projects/juggluco/files/Juggluco-$1.apk/download"; }
ROOT="$(cd "$(dirname "$0")/.." && pwd)"

force=false
apk=""
for arg in "$@"; do
  case "$arg" in
    --force) force=true ;;
    -h|--help) sed -n '2,33p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) apk="$arg" ;;
  esac
done

# libcrl_dp.so ships arm64-only in Juggluco; the 32-bit (Wear OS) engine never
# dlopens it, so it is required for arm64-v8a only.
ABIS=(arm64-v8a armeabi-v7a)
required() { # $1=abi -> repo-relative paths that must exist for that abi
  echo "Common/src/main/jniLibs/$1/libcalibrat2.so"
  echo "Common/src/libre3/jniLibs/$1/libinit.so"
  echo "Common/src/libre3/jniLibs/$1/liblibre3extension.so"
  [ "$1" = "arm64-v8a" ] && echo "Common/src/libre3/jniLibs/$1/libcrl_dp.so"
  return 0
}
dest_for() { # $1=filename -> repo-relative jniLibs parent dir ("" = ignore)
  case "$1" in
    libcalibrat2.so|libcalibrate.so) echo "Common/src/main/jniLibs" ;;
    libinit.so|liblibre3extension.so|libcrl_dp.so) echo "Common/src/libre3/jniLibs" ;;
    *) echo "" ;;
  esac
}

list_missing() {
  local abi f
  for abi in "${ABIS[@]}"; do
    while IFS= read -r f; do
      [ -f "$ROOT/$f" ] || echo "$f"
    done < <(required "$abi")
  done
}

if [ -z "$(list_missing)" ] && [ "$force" = "false" ]; then
  echo "extract-blobs: all sensor libraries already present — nothing to do."
  exit 0
fi

tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT

# extract <apk> — copy any recognised .so from the APK into its jniLibs dir.
extract() {
  local src="$1" copied=0 abi so name dest
  echo "extract-blobs: extracting native libraries from $(basename "$src")…"
  unzip -q -o "$src" 'lib/*' -d "$tmp/apk"
  for abi in "${ABIS[@]}"; do
    for so in "$tmp/apk/lib/$abi"/*.so; do
      [ -f "$so" ] || continue
      name="$(basename "$so")"
      dest="$(dest_for "$name")"
      [ -n "$dest" ] || continue
      mkdir -p "$ROOT/$dest/$abi"
      cp "$so" "$ROOT/$dest/$abi/$name"
      echo "  $abi/$name -> $dest/$abi/"
      copied=$((copied + 1))
    done
  done
  rm -rf "$tmp/apk"
  [ "$copied" -gt 0 ] || { echo "extract-blobs: no known libraries found in $(basename "$src")." >&2; return 1; }
}

fetch() { # $1=version -> path to downloaded apk
  local ver="$1" out="$tmp/Juggluco-$1.apk"
  echo "extract-blobs: downloading Juggluco ${ver} APK…" >&2
  curl -fL --retry 3 --progress-bar -o "$out" "$(sf "$ver")"
  echo "$out"
}

if [ -n "$apk" ]; then
  # User supplied an APK: extract what it has. A multi-arch APK <=10.6.x has all four.
  [ -f "$apk" ] || { echo "extract-blobs: APK not found: $apk" >&2; exit 1; }
  extract "$apk"
else
  extract "$(fetch "$VERSION")"
fi

# libcrl_dp.so is absent from current APKs — pull it from the pinned older one.
if [ ! -f "$ROOT/Common/src/libre3/jniLibs/arm64-v8a/libcrl_dp.so" ]; then
  echo "extract-blobs: libcrl_dp.so not in that APK — fetching from Juggluco ${CRL_VERSION}…"
  extract "$(fetch "$CRL_VERSION")"
fi

missing="$(list_missing)"
if [ -n "$missing" ]; then
  echo "extract-blobs: ERROR — still missing after extraction:" >&2
  echo "$missing" | sed 's/^/  /' >&2
  echo "Supply an official multi-arch Juggluco APK, or set JUGGLUCO_VERSION / JUGGLUCO_CRL_VERSION." >&2
  exit 1
fi
echo "extract-blobs: done — all sensor libraries in place."
