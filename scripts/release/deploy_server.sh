#!/bin/bash
# Safe deploy of the play stack (incl. the alieknek bot) on play.juliussze.at, run from this PC after the play repo's
# ENGINE_REF / ENGINE_JAR are pushed:
#   1. pause the bot (botctl off) and wait until it is not in a game (at most WAIT_MIN minutes, default 40),
#   2. run deploy.sh on the server,
#   3. switch the bot on again - ALWAYS, also when a step fails or the script is interrupted (unless it was
#      switched off before the deploy: then it stays off).
# Every ssh call has a connect timeout, keepalive and an overall time limit: a dropped connection once left the
# bot paused for 75 minutes while a plain ssh waited forever.
set -u
KEY="${KEY:-$HOME/.ssh/id_ed25519_hetzner}"
HOST="${HOST:-root@play.juliussze.at}"
WAIT_MIN="${WAIT_MIN:-40}"
SSH=(ssh -o BatchMode=yes -o ConnectTimeout=20 -o ServerAliveInterval=15 -o ServerAliveCountMax=4 -i "$KEY" "$HOST")
STATE='docker exec lostopf-alieknek-1 /opt/venv/bin/python -c "import json,urllib.request; print(json.load(urllib.request.urlopen(\"http://localhost:8096/control\", timeout=10)).get(\"state\"))"'
ENABLED='docker exec lostopf-alieknek-1 /opt/venv/bin/python -c "import json,urllib.request; print(json.load(urllib.request.urlopen(\"http://localhost:8096/control\", timeout=10)).get(\"enabled\"))"'

# Only a bot that was switched on before is switched on again (someone may have switched it off on purpose).
was_enabled=$(timeout 60 "${SSH[@]}" "$ENABLED" 2>/dev/null | tr -d '\r')
echo "[deploy] bot enabled before: ${was_enabled:-unknown}"
bot_on() {
  if [ "$was_enabled" = "False" ]; then
    echo "[deploy] the bot was off before - leaving it off"
    return
  fi
  echo "[deploy] switching the bot on"
  timeout 120 "${SSH[@]}" 'docker exec lostopf-alieknek-1 botctl on' >/dev/null || echo "[deploy] WARNING: botctl on failed"
}
trap bot_on EXIT

# Always the newest Alieknek release: the play repo's alieknek/Dockerfile is pinned to the head of the highest
# vX.Y.Z branch on GitHub (pushed only when a version is released) - updated, committed and pushed here if not.
PLAY="${PLAY:-/c/Development/Play/play}"
ENGINE_REPO="$(cd "$(dirname "$0")/../.." && pwd)"
latest=$(git -C "$ENGINE_REPO" ls-remote --heads origin 'v*' | sed 's#.*refs/heads/##' \
  | grep -E '^v[0-9]+\.[0-9]+\.[0-9]+$' | sort -t. -k1.2,1n -k2,2n -k3,3n | tail -1)
if [ -n "$latest" ] && [ -f "$PLAY/alieknek/Dockerfile" ]; then
  git -C "$ENGINE_REPO" fetch -q origin "$latest" || { echo "[deploy] fetch $latest failed"; exit 1; }
  ref=$(git -C "$ENGINE_REPO" rev-parse FETCH_HEAD)
  version=$(git -C "$ENGINE_REPO" show "$ref:src/main/java/julius/game/chessengine/core/Uci.java" \
    | sed -n 's/.*NAME = "Alieknek \([0-9.]*\)".*/\1/p' | head -1)
  git -C "$PLAY" pull -q --ff-only origin main || { echo "[deploy] play repo pull failed"; exit 1; }
  pinned=$(sed -n 's/^ARG ENGINE_REF=//p' "$PLAY/alieknek/Dockerfile")
  if [ -n "$version" ] && [ "$pinned" != "$ref" ]; then
    echo "[deploy] pinning the play repo to Alieknek $version ($latest ${ref:0:8})"
    sed -i "s#^ARG ENGINE_REF=.*#ARG ENGINE_REF=$ref#; s#^ARG ENGINE_JAR=.*#ARG ENGINE_JAR=chess-engine-$version-uci.jar#" \
      "$PLAY/alieknek/Dockerfile"
    sed -i "s#Versionsplakette \`v[0-9.]*\`#Versionsplakette \`v$version\`#" "$PLAY/CLAUDE.md"
    git -C "$PLAY" add alieknek/Dockerfile CLAUDE.md
    git -C "$PLAY" commit -q -m "Alieknek: Engine $version (neueste Version, beim Einspielen automatisch gesetzt)" \
      && git -C "$PLAY" push -q origin main || { echo "[deploy] pinning failed"; exit 1; }
  else
    echo "[deploy] play repo already on the newest Alieknek ($latest)"
  fi
fi

echo "[deploy] pausing the bot"
timeout 120 "${SSH[@]}" 'docker exec lostopf-alieknek-1 botctl off' >/dev/null || { echo "[deploy] botctl off failed"; exit 1; }
deadline=$(( $(date +%s) + WAIT_MIN * 60 ))
while :; do
  state=$(timeout 60 "${SSH[@]}" "$STATE" 2>/dev/null | tr -d '\r')
  echo "[deploy] bot state: ${state:-unknown}"
  [ -n "$state" ] && [ "$state" != "playing" ] && break
  [ "$(date +%s)" -ge "$deadline" ] && { echo "[deploy] still in a game after $WAIT_MIN min - giving up"; exit 1; }
  sleep 30
done
echo "[deploy] running deploy.sh"
timeout 1800 "${SSH[@]}" '/opt/lostopf/repo/deploy/scripts/deploy.sh main' || { echo "[deploy] deploy.sh failed"; exit 1; }
sleep 20
echo "[deploy] done"
