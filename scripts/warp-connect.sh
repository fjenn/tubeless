#!/usr/bin/env bash
# CI only: register Cloudflare WARP (already installed) in SOCKS proxy mode on 127.0.0.1:40000 and wait
# until traffic goes out through it. Used by stream-health.yml.
set -uo pipefail
for _ in 1 2 3 4 5; do warp-cli --accept-tos registration new && break; sleep 3; done
warp-cli --accept-tos mode proxy
warp-cli --accept-tos connect
for _ in $(seq 1 20); do
  curl -s --max-time 5 -x socks5h://127.0.0.1:40000 https://www.cloudflare.com/cdn-cgi/trace | grep -q "warp=on" && break
  sleep 2
done
curl -s --max-time 10 -x socks5h://127.0.0.1:40000 https://www.cloudflare.com/cdn-cgi/trace | grep -E "warp=|loc="
