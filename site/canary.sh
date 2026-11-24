#!/usr/bin/env bash
# Instant mode's two outside dependencies, checked the way a browser page meets them (site/instant.js):
#  1. InnerTube answers a simple text/plain POST from Origin "null" with "Access-Control-Allow-Origin: null"
#     (visitor_id and player, the sandboxed bridge's calls), and refuses a foreign origin (so the trick is the reason);
#  2. a stream URL from the first player client in clients.json (VISIONOS) opens with an open-ended range (<video src>).
# Exit 0: works. Exit 1: broken (the workflow opens an issue). Exit 2: inconclusive (this address is bot-walled).
set -uo pipefail
cd "$(dirname "$0")/.."
UA='Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/142.0.0.0 Safari/537.36'
VIDEOS="${CANARY_VIDEOS:-aqz-KE-bpKQ jNQXAC9IVRw dQw4w9WgXcQ}"
T="$(mktemp -d)"; trap 'rm -rf "$T"' EXIT
post() { # url body → headers in $T/h, body in $T/b
  curl -s --max-time 20 -D "$T/h" -o "$T/b" -A "$UA" -H 'Origin: null' -H 'Content-Type: text/plain;charset=UTF-8' --data "$2" "$1"
}
acao() { tr -d '\r' < "$T/h" | awk -F': ' 'tolower($1)=="access-control-allow-origin"{print $2}'; }
status() { head -1 "$T/h" | awk '{print $2}'; }

post 'https://www.youtube.com/youtubei/v1/visitor_id?prettyPrint=false' '{"context":{"client":{"clientName":"WEB","clientVersion":"2.20251120.01.00","hl":"en","gl":"US"}}}'
if [ "$(status)" != 200 ] || [ "$(acao)" != null ]; then
  echo "BROKEN: visitor_id from Origin null answered $(status), ACAO '$(acao)'"; exit 1
fi
VD="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["responseContext"]["visitorData"])' "$T/b")"
echo "ok: InnerTube answers Origin null (ACAO null), visitor ${VD:0:12}…"

CLIENT="$(python3 - <<'PY'
import json
p = json.load(open("clients.json"))["players"][0]
c = {"clientName": p["name"], "clientVersion": p["version"], "hl": "en", "gl": "US"}
c.update(p.get("extra", {}))
print(json.dumps(c))
PY
)"
walled=0
for id in $VIDEOS; do
  BODY="$(python3 -c 'import json,sys; c=json.loads(sys.argv[1]); c["visitorData"]=sys.argv[2]; print(json.dumps({"context":{"client":c},"videoId":sys.argv[3],"contentCheckOk":True,"racyCheckOk":True}))' "$CLIENT" "$VD" "$id")"
  post 'https://www.youtube.com/youtubei/v1/player?prettyPrint=false' "$BODY"
  if [ "$(status)" != 200 ] || [ "$(acao)" != null ]; then echo "BROKEN: player from Origin null answered $(status), ACAO '$(acao)'"; exit 1; fi
  read -r PS URL < <(python3 - "$T/b" <<'PY'
import json, sys
d = json.load(open(sys.argv[1]))
ps = d.get("playabilityStatus", {})
st = ps.get("status", "?")
if st == "LOGIN_REQUIRED" and "bot" in ps.get("reason", ""): st = "BOTWALL"
fs = [f for f in d.get("streamingData", {}).get("adaptiveFormats", []) if f.get("url") and f.get("mimeType", "").startswith("video/")]
fs.sort(key=lambda f: -f.get("height", 0))
print(st, fs[0]["url"] if fs else "-")
PY
)
  if [ "$PS" = BOTWALL ]; then echo "walled: $id (this address is asked to sign in)"; walled=$((walled + 1)); continue; fi
  if [ "$PS" != OK ] || [ "$URL" = - ]; then echo "BROKEN: $id: player status $PS, no direct stream URL"; exit 1; fi
  CODE="$(curl -s --max-time 20 -o /dev/null -w '%{http_code}' -A "$UA" -r 0-65535 "$URL")"
  if [ "$CODE" != 206 ] && [ "$CODE" != 200 ]; then echo "BROKEN: $id: stream URL answered $CODE"; exit 1; fi
  CODE2="$(curl -s --max-time 10 -o /dev/null -w '%{http_code}' -A "$UA" -H 'Range: bytes=0-' --max-filesize 1 "$URL" || true)"
  echo "ok: $id plays through $(python3 -c 'import json;print(json.load(open("clients.json"))["players"][0]["name"])') (range 0-65535: $CODE, open range: $CODE2)"
  exit 0
done
echo "INCONCLUSIVE: every video was bot-walled from this address ($walled)"; exit 2
