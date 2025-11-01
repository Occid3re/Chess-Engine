#!/usr/bin/env bash
# download_syzygy.sh — fetch selected Lichess Syzygy tablebase folders using curl
# Works in Git Bash / MINGW64 on Windows.

set -euo pipefail

BASE="https://tablebase.lichess.ovh/tables/standard"
FOLDERS=(
  "3-4-5-dtz"
  "3-4-5-dtz-nr"
  "3-4-5-wdl"
  "6-dtz"
  "6-dtz-nr"
  "6-wdl"
)

download_folder() {
  local folder="$1"
  local url="$BASE/$folder/"

  mkdir -p "$folder"
  cd "$folder"

  echo "==> Indexing $url"
  # List .rtbz / .rtbw entries on the index page and download each, resuming if partial
  curl -fsSL "$url" \
  | grep -oE 'href="[^"]+"' \
  | cut -d'"' -f2 \
  | grep -E '\.(rtbz|rtbw)$' \
  | while IFS= read -r f; do
      echo "-> $folder/$f"
      # -f fail on HTTP errors, -L follow redirects, --retry for robustness, -C - resume, -O keep filename
      curl -fL --retry 5 --retry-delay 2 -C - -O "$url$f"
    done

  cd - >/dev/null
}

for d in "${FOLDERS[@]}"; do
  download_folder "$d"
done

echo "All requested folders processed."
