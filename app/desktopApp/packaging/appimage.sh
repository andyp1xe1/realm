#!/usr/bin/env bash
# Wraps the app folder from `./gradlew :desktopApp:createDistributable` (build/compose/binaries/main/app/realm,
# the app with its own Java runtime) into one AppImage.
# Usage: appimage.sh <app folder> <output .AppImage>. Needs appimagetool on PATH.
set -euo pipefail

app=$1
out=$2
here=$(cd "$(dirname "$0")" && pwd)

appdir=$(mktemp -d)
trap 'rm -rf "$appdir"' EXIT
cp -a "$app"/. "$appdir"/
cp "$here/realm.png" "$appdir/realm.png"
ln -s realm.png "$appdir/.DirIcon"
cat >"$appdir/realm.desktop" <<'EOF'
[Desktop Entry]
Type=Application
Name=realm
Comment=A personal dump for notes and files
Exec=realm
Icon=realm
Categories=Utility;
Terminal=false
EOF
cat >"$appdir/AppRun" <<'EOF'
#!/bin/sh
exec "$(dirname "$(readlink -f "$0")")/bin/realm" "$@"
EOF
chmod +x "$appdir/AppRun"

ARCH=x86_64 appimagetool --no-appstream "$appdir" "$out"
