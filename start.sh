#!/usr/bin/env bash
# Runs claude-code-coach locally with one command: ./start.sh
# Asks for your Google AI Studio key if it can't find one, picks a free backend port (so Jenkins or
# anything else on 8080 doesn't matter), installs frontend packages, starts both halves.
# Press Ctrl+C to stop everything.
set -eu
cd "$(dirname "$0")"
ROOT="$(pwd)"

say() { printf '\n\033[1m==> %s\033[0m\n' "$1"; }
fail() { printf '\n\033[31mERROR: %s\033[0m\n' "$1" >&2; exit 1; }

# 1. Tools
say "Checking tools"
command -v java >/dev/null || fail "Java not found. Install JDK 21 or newer."
JAVA_MAJOR=$(java -version 2>&1 | awk -F'"' '/version/ {split($2, v, "."); print (v[1] == "1" ? v[2] : v[1]); exit}')
[ "${JAVA_MAJOR:-0}" -ge 21 ] || fail "Java $JAVA_MAJOR found; this app needs 21 or newer."
command -v node >/dev/null || fail "Node.js not found. Install Node 20.19+ or 22.12+."
NODE_MAJOR=$(node -v | sed 's/^v//' | cut -d. -f1)
[ "$NODE_MAJOR" -ge 20 ] || fail "Node $(node -v) found; this app needs 20.19+ or 22.12+."
command -v curl >/dev/null || fail "curl not found. Install it (sudo apt install curl)."
echo "Java $JAVA_MAJOR, Node $(node -v)"

# 2. API key: the one thing you have to provide
if [ -z "${GEMINI_API_KEY:-}" ] && ! grep -qsE '^GEMINI_API_KEY=.+' .env backend/.env; then
  say "Google AI Studio API key needed (free: https://aistudio.google.com/apikey)"
  [ -f .env ] || cp .env.example .env
  KEY=""
  while [ -z "$KEY" ]; do
    printf 'Paste your key and press Enter: '
    read -r KEY
    KEY=$(printf '%s' "$KEY" | tr -d '[:space:]"'"'")
  done
  awk -v k="$KEY" 'BEGIN{done=0} /^GEMINI_API_KEY=/{print "GEMINI_API_KEY=" k; done=1; next} {print} END{if(!done) print "GEMINI_API_KEY=" k}' .env > .env.tmp
  mv .env.tmp .env
  echo "Saved to .env (git ignores this file)."
fi

# 3. A free backend port: BACKEND_PORT if set, else 8080; move up if taken
port_busy() { (echo > "/dev/tcp/127.0.0.1/$1") 2>/dev/null; }
PORT=${BACKEND_PORT:-$(grep -sE '^BACKEND_PORT=[0-9]+' .env | tail -1 | cut -d= -f2)}
PORT=${PORT:-8080}
START_PORT=$PORT
while port_busy "$PORT"; do PORT=$((PORT + 1)); done
[ "$PORT" = "$START_PORT" ] || echo "Port $START_PORT is taken (Jenkins?), using $PORT instead."
export BACKEND_PORT=$PORT

# 4. Frontend packages (first run only)
if [ ! -d frontend/node_modules ]; then
  say "Installing frontend packages (first run only, about a minute)"
  (cd frontend && npm ci --no-audit --no-fund)
fi

# 5. Backend in the background, logging to backend.log
say "Starting backend on port $PORT (first run downloads dependencies, a few minutes)"
set -m   # own process group, so Ctrl+C can stop Maven and the JVM it forks
(cd backend && exec ./mvnw -q spring-boot:run) > backend.log 2>&1 &
BACKEND_PID=$!
cleanup() {
  trap - EXIT INT TERM
  echo; echo "Stopping backend..."
  kill -- -"$BACKEND_PID" 2>/dev/null || kill "$BACKEND_PID" 2>/dev/null || true
}
trap cleanup EXIT INT TERM

printf 'Waiting for the backend'
until curl -sf "http://localhost:$PORT/api/health/liveness" >/dev/null; do
  if ! kill -0 "$BACKEND_PID" 2>/dev/null; then
    echo; echo "The backend stopped. Last lines of backend.log:"; echo
    grep -A6 'APPLICATION FAILED TO START' backend.log || tail -25 backend.log
    exit 1
  fi
  printf '.'; sleep 2
done
echo " ready."
echo "The Claude Code docs index builds in the background (about 20 seconds)."
echo "Backend log: $ROOT/backend.log"

# 6. Frontend in the foreground
say "Starting frontend. Open the Local URL below in your browser. Ctrl+C stops everything."
cd frontend
npm run dev
