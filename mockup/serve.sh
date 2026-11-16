#!/usr/bin/env bash
# Serve the NexusClone UI mockup on 127.0.0.1:8453 and publish it tailnet-only.
set -euo pipefail

DIR="$(cd "$(dirname "$0")" && pwd)"
PORT=8453

if ! ss -ltn | grep -q ":$PORT "; then
  setsid nohup python3 -m http.server "$PORT" --bind 127.0.0.1 --directory "$DIR" \
    >/tmp/nc-mockup.log 2>&1 &
  sleep 1
fi

curl -fsS -o /dev/null "http://127.0.0.1:$PORT/index.html" && echo "local  ok  http://127.0.0.1:$PORT"

tailscale serve --bg --https=$PORT "http://127.0.0.1:$PORT" >/dev/null
echo "tailnet ok  https://kaizerfx.tail8b5e67.ts.net:$PORT"
