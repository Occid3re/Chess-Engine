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
