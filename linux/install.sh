#!/bin/sh
# Install LiDio for the current user (launcher, icon, `lidio` command).
set -e
cd "$(dirname "$0")"
ROOT="$(pwd)"
APP_ID=io.github.veritasx1.LiDio
DATA="${XDG_DATA_HOME:-$HOME/.local/share}"
mkdir -p "$HOME/.local/bin" "$DATA/applications" "$DATA/icons/hicolor/scalable/apps"
cat > "$HOME/.local/bin/lidio" <<SCRIPT
#!/bin/sh
exec /usr/bin/python3 "$ROOT/main.py" "\$@"
SCRIPT
chmod +x "$HOME/.local/bin/lidio"
cp "data/$APP_ID.svg" "$DATA/icons/hicolor/scalable/apps/$APP_ID.svg"
sed "s|@EXEC@|$HOME/.local/bin/lidio|" "data/$APP_ID.desktop.in" > "$DATA/applications/$APP_ID.desktop"
update-desktop-database "$DATA/applications" 2>/dev/null || true
# PNG versions for places that do not render SVG icons.
python3 - "$DATA" "data/$APP_ID.svg" "$APP_ID" <<'PY' || true
import sys, gi
gi.require_version("GdkPixbuf", "2.0")
from gi.repository import GdkPixbuf
data, svg, app_id = sys.argv[1:]
import os
for size in (32, 48, 64, 128, 256, 512):
    folder = f"{data}/icons/hicolor/{size}x{size}/apps"
    os.makedirs(folder, exist_ok=True)
    GdkPixbuf.Pixbuf.new_from_file_at_size(svg, size, size).savev(f"{folder}/{app_id}.png", "png", [], [])
PY
# -t: the user icon folder has no index.theme; without it the cache is not rebuilt.
gtk-update-icon-cache -q -f -t "$DATA/icons/hicolor" 2>/dev/null || true
# Milkdrop (optional): projectM 4 is built once from source – needs cmake, g++ and libgl-dev.
if [ ! -f lib/libprojectM-4.so.4 ]; then
  if command -v cmake >/dev/null && command -v g++ >/dev/null; then
    ./native/build-milk.sh || echo "Milkdrop übersprungen (Bauen ging nicht) – LiDio läuft trotzdem."
  else
    echo "Milkdrop übersprungen: für die Visualisierung einmal 'sudo apt install cmake g++ libgl-dev' und install.sh erneut."
  fi
fi
# The private variant – only where its package is present (never in the published source).
if [ -d lidio/privat ]; then
  cat > "$HOME/.local/bin/lidio-privat" <<SCRIPT
#!/bin/sh
LIDIO_PRIVAT=1 exec /usr/bin/python3 "$ROOT/main.py" "\$@"
SCRIPT
  chmod +x "$HOME/.local/bin/lidio-privat"
  cp "data/$APP_ID.Privat.svg" "$DATA/icons/hicolor/scalable/apps/$APP_ID.Privat.svg"
  sed "s|@EXEC@|$HOME/.local/bin/lidio-privat|" "data/$APP_ID.Privat.desktop.in" > "$DATA/applications/$APP_ID.Privat.desktop"
  python3 -c "
import sys, gi; gi.require_version('GdkPixbuf', '2.0')
from gi.repository import GdkPixbuf
import os
for size in (32, 48, 64, 128, 256, 512):
    f = '$DATA/icons/hicolor/%dx%d/apps' % (size, size); os.makedirs(f, exist_ok=True)
    GdkPixbuf.Pixbuf.new_from_file_at_size('data/$APP_ID.Privat.svg', size, size).savev(f + '/$APP_ID.Privat.png', 'png', [], [])
" || true
  update-desktop-database "$DATA/applications" 2>/dev/null || true
  gtk-update-icon-cache -q -f -t "$DATA/icons/hicolor" 2>/dev/null || true
  echo "LiDio privat installiert – Befehl: lidio-privat"
fi
echo "LiDio installiert – im Anwendungsmenü oder mit: lidio"
