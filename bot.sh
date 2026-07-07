#!/usr/bin/env bash
#
# bot.sh — sobe toda a infra do CyreneBot e gerencia o ciclo de vida do processo.
#
#   ./bot.sh start      # sobe infra (Postgres, Ollama, colima+SearXNG) e inicia o bot
#   ./bot.sh restart    # recompila e reinicia SÓ o bot (infra fica de pé) — use após mudar código
#   ./bot.sh stop       # para o bot.  `stop --all` também derruba SearXNG/colima
#   ./bot.sh status     # mostra o que está no ar
#   ./bot.sh logs       # tail -f do log do bot
#   ./bot.sh reindex    # (re)constrói a base de conhecimento HSR e sai
#
# O bot roda em background; PID em .bot.pid, logs em logs/bot.log.

set -uo pipefail

# ---- localização / paths ----
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"
export PATH="/opt/homebrew/bin:/opt/homebrew/opt/postgresql@14/bin:$PATH"

PID_FILE="$SCRIPT_DIR/.bot.pid"
LOG_DIR="$SCRIPT_DIR/logs"
LOG_FILE="$LOG_DIR/bot.log"
JAR="$SCRIPT_DIR/target/cyrene-bot-2.0.0.jar"
SEARXNG_DIR="$SCRIPT_DIR/docker/searxng"
mkdir -p "$LOG_DIR"

# ---- cores ----
if [ -t 1 ]; then C_OK=$'\033[32m'; C_WARN=$'\033[33m'; C_ERR=$'\033[31m'; C_DIM=$'\033[2m'; C_B=$'\033[1m'; C_0=$'\033[0m'
else C_OK=; C_WARN=; C_ERR=; C_DIM=; C_B=; C_0=; fi
ok()   { echo "${C_OK}✓${C_0} $*"; }
warn() { echo "${C_WARN}!${C_0} $*"; }
err()  { echo "${C_ERR}✗${C_0} $*" >&2; }
step() { echo "${C_B}▶ $*${C_0}"; }

# ---- pré-requisitos ----
require_env() {
  [ -f "$SCRIPT_DIR/.env" ] || { err ".env não encontrado. Copie de .env.example."; exit 1; }
  set -a; # shellcheck disable=SC1091
  source "$SCRIPT_DIR/.env"; set +a
  : "${DB_USER:=cyrene}" "${DB_PASSWORD:=cyrene}"
  : "${MODEL_NAME:=qwen2.5:14b-instruct-q4_K_M}" "${EMBED_MODEL_NAME:=nomic-embed-text}"
  : "${OLLAMA_BASE_URL:=http://localhost:11434}"
}

resolve_java() {
  local jh
  if jh="$(/usr/libexec/java_home -v 21 2>/dev/null)"; then          # macOS
    export JAVA_HOME="$jh"
  elif [ -n "${JAVA_HOME:-}" ] && "$JAVA_HOME/bin/java" -version 2>&1 | grep -q 'version "21'; then
    export JAVA_HOME                                                 # JAVA_HOME já aponta pro 21
  elif command -v java >/dev/null 2>&1 && java -version 2>&1 | grep -q 'version "21'; then
    # java 21 no PATH — deriva JAVA_HOME dele (sobrescreve um JAVA_HOME antigo que o mvn usaria)
    export JAVA_HOME="$(dirname "$(dirname "$(readlink -f "$(command -v java)")")")"
  else
    err "JDK 21 não encontrado (Kotlin 2.1.0 exige)."
    echo "  macOS: brew install --cask corretto@21" >&2
    echo "  Linux: apt install openjdk-21-jdk (ou equivalente) e/ou aponte JAVA_HOME pra ele" >&2
    exit 1
  fi
}

wait_for() { # wait_for <desc> <cmd...>  — tenta por ~30s
  local desc="$1"; shift
  for _ in $(seq 1 30); do "$@" >/dev/null 2>&1 && return 0; sleep 1; done
  warn "timeout esperando: $desc"; return 1
}

# ---- infra ----
# Data dir do Postgres (onde vive o postmaster.pid) — portável mac/linux.
# Ordem: $PGDATA explícito > homebrew > caminhos comuns de distro Linux.
pg_data_dir() {
  [ -n "${PGDATA:-}" ] && { echo "$PGDATA"; return; }
  if command -v brew >/dev/null 2>&1; then
    local d="$(brew --prefix)/var/postgresql@14"; [ -d "$d" ] && { echo "$d"; return; }
  fi
  local d; for d in /var/lib/postgresql/*/main /usr/local/var/postgresql@14 /var/lib/pgsql/data; do
    [ -f "$d/PG_VERSION" ] && { echo "$d"; return; }
  done
}

# Postgres que morre sem limpar deixa um postmaster.pid órfão; o SO pode ter reciclado
# aquele PID pra outro processo → o postgres se recusa a subir ("lock file already exists").
# Remove o pid se ele não aponta mais pra um postgres vivo.
# ponytail: só cobre postgres local (dev/homebrew) — num postgres de sistema o data dir é
# de outro dono e o systemd já limpa sozinho.
clear_stale_pid() {
  local dir pidfile pid
  dir="$(pg_data_dir)"; [ -n "$dir" ] || return 0
  pidfile="$dir/postmaster.pid"; [ -f "$pidfile" ] || return 0
  pid="$(head -1 "$pidfile" 2>/dev/null)"
  if kill -0 "$pid" 2>/dev/null && ps -p "$pid" -o comm= 2>/dev/null | grep -qiE 'postgres|postmaster'; then
    return 0   # PID é um postgres vivo de verdade — não mexe
  fi
  warn "postmaster.pid órfão em $dir (PID $pid não é postgres) — removendo"
  rm -f "$pidfile"
}

start_postgres() {
  if pg_isready -h localhost -q 2>/dev/null; then ok "Postgres já no ar"; return; fi
  clear_stale_pid
  step "Subindo Postgres"
  if command -v brew >/dev/null 2>&1 && brew list postgresql@14 >/dev/null 2>&1; then
    brew services start postgresql@14 >/dev/null 2>&1
  elif command -v pg_ctl >/dev/null 2>&1 && [ -n "$(pg_data_dir)" ]; then
    pg_ctl -D "$(pg_data_dir)" -l "$LOG_DIR/postgres.log" start >/dev/null 2>&1
  elif command -v systemctl >/dev/null 2>&1; then
    sudo systemctl start postgresql >/dev/null 2>&1 || systemctl start postgresql >/dev/null 2>&1
  else
    warn "não sei subir o Postgres neste ambiente — inicie manualmente e rode de novo"; return 0
  fi
  wait_for "Postgres" pg_isready -h localhost -q && ok "Postgres no ar"
}

start_ollama() {
  if curl -sf "$OLLAMA_BASE_URL/api/tags" >/dev/null 2>&1; then ok "Ollama já no ar"
  else step "Subindo Ollama"; nohup ollama serve >"$LOG_DIR/ollama.log" 2>&1 &
       wait_for "Ollama" curl -sf "$OLLAMA_BASE_URL/api/tags" && ok "Ollama no ar"; fi
  # checagem de modelos (não baixa sozinho — só avisa)
  local tags; tags="$(curl -sf "$OLLAMA_BASE_URL/api/tags" 2>/dev/null)"
  for m in "$EMBED_MODEL_NAME" "$MODEL_NAME"; do
    echo "$tags" | grep -q "\"${m%%:*}" || warn "modelo '$m' não encontrado — rode: ollama pull $m"
  done
}

# compose v2 é plugin (`docker compose`); o binário standalone `docker-compose` só existe
# em instalações antigas/brew. Usa o que houver.
compose() {
  if docker compose version >/dev/null 2>&1; then docker compose "$@"; else docker-compose "$@"; fi
}

start_searxng() {
  [ -n "${SEARXNG_URL:-}" ] || { warn "SEARXNG_URL vazio — web search desligado (lookupHsr ainda funciona)"; return 0; }
  # Linux roda docker nativo; colima é só a VM do docker no macOS.
  if ! docker info >/dev/null 2>&1; then
    if command -v colima >/dev/null 2>&1; then
      if ! colima status 2>&1 | grep -qi running; then step "Subindo colima"; colima start >/dev/null 2>&1; fi
    else warn "docker indisponível (daemon parado e sem colima) — web search desligado"; return 0; fi
  fi
  if curl -sf "$SEARXNG_URL/" >/dev/null 2>&1; then ok "SearXNG já no ar ($SEARXNG_URL)"
  else step "Subindo SearXNG"; (cd "$SEARXNG_DIR" && compose up -d >/dev/null 2>&1)
       wait_for "SearXNG" curl -sf "$SEARXNG_URL/" && ok "SearXNG no ar ($SEARXNG_URL)"; fi
}

# ---- ciclo de vida do bot ----
bot_running() { [ -f "$PID_FILE" ] && kill -0 "$(cat "$PID_FILE")" 2>/dev/null; }

# Detecta QUALQUER instância do bot que não seja a nossa (.bot.pid) — tipicamente um run
# do IntelliJ esquecido. Duas instâncias no mesmo BOT_TOKEN = o Discord entrega a mensagem
# pra uma delas aleatoriamente; se a outra for código antigo, você vê respostas erradas
# de forma intermitente (exatamente o bug do RAG "que parou de funcionar").
foreign_instances() {
  local self; self="$(cat "$PID_FILE" 2>/dev/null || echo -1)"
  # `--` impede o grep de tratar o sentinela "-1" (sem .bot.pid) como flag.
  { pgrep -f "cyrene-bot-2.0.0.jar"; pgrep -f "CyreneBotApplicationKt"; } 2>/dev/null \
    | sort -u | grep -vx -- "$self"
}

assert_single_instance() {
  local others; others="$(foreign_instances)"
  [ -z "$others" ] && return 0
  err "Já existe outra instância do bot rodando (PID: $(echo "$others" | tr '\n' ' '))."
  echo "  Provavelmente um run do IntelliJ. Pare-o (botão ⏹) ou mate: kill $(echo "$others" | tr '\n' ' ')"
  echo "  Duas instâncias no mesmo token causam respostas erradas intermitentes."
  return 1
}

build() {
  step "Compilando (mvn package)"
  if mvn -q clean package -DskipTests; then ok "Build OK"; else err "Build falhou — bot NÃO reiniciado"; return 1; fi
}

start_bot() {
  if bot_running; then warn "Bot já rodando (PID $(cat "$PID_FILE")). Use restart."; return 0; fi
  assert_single_instance || return 1
  [ -f "$JAR" ] || build || return 1
  step "Iniciando bot"
  nohup java -jar "$JAR" >"$LOG_FILE" 2>&1 &
  echo $! > "$PID_FILE"
  if wait_for "bot" grep -q "Started CyreneBot" "$LOG_FILE"; then
    ok "Bot no ar (PID $(cat "$PID_FILE")) — logs: ./bot.sh logs"
  else
    err "Bot não confirmou startup; veja ./bot.sh logs"; tail -15 "$LOG_FILE"; return 1
  fi
}

stop_bot() {
  if bot_running; then
    local pid; pid="$(cat "$PID_FILE")"
    step "Parando bot (PID $pid)"; kill "$pid" 2>/dev/null
    for _ in $(seq 1 10); do kill -0 "$pid" 2>/dev/null || break; sleep 1; done
    kill -0 "$pid" 2>/dev/null && kill -9 "$pid" 2>/dev/null
    ok "Bot parado"
  else warn "Bot não estava rodando"; fi
  rm -f "$PID_FILE"
}

# ---- comandos ----
cmd_start() {
  require_env; resolve_java
  start_postgres; start_ollama; start_searxng
  start_bot
}

cmd_restart() { # recompila e reinicia só o bot — infra intacta
  require_env; resolve_java
  step "Restart: recompilando e reiniciando o bot"
  build || return 1
  stop_bot
  start_bot
}

cmd_stop() {
  stop_bot
  if [ "${1:-}" = "--all" ]; then
    require_env
    step "Derrubando SearXNG"; (cd "$SEARXNG_DIR" && compose down >/dev/null 2>&1) && ok "SearXNG parado"
    warn "colima/docker e Postgres deixados de pé (compartilhados). Pare manualmente se quiser:"
    echo "    colima stop   |   brew services stop postgresql@14   (Linux: systemctl stop postgresql)"
  fi
}

cmd_status() {
  require_env
  echo "${C_B}Infra${C_0}"
  pg_isready -h localhost -q 2>/dev/null && ok "Postgres" || err "Postgres"
  curl -sf "$OLLAMA_BASE_URL/api/tags" >/dev/null 2>&1 && ok "Ollama" || err "Ollama"
  if [ -n "${SEARXNG_URL:-}" ]; then curl -sf "$SEARXNG_URL/" >/dev/null 2>&1 && ok "SearXNG ($SEARXNG_URL)" || err "SearXNG ($SEARXNG_URL)"
  else warn "SearXNG desligado (SEARXNG_URL vazio)"; fi
  local docs; docs="$(PGPASSWORD="$DB_PASSWORD" psql -h localhost -U "$DB_USER" -d cyrene -tAc 'select count(*) from vector_store' 2>/dev/null)"
  [ -n "$docs" ] && echo "  ${C_DIM}vector_store: $docs docs${C_0}"
  echo "${C_B}Bot${C_0}"
  bot_running && ok "Bot rodando (PID $(cat "$PID_FILE"))" || warn "Bot parado (via script)"
  local others; others="$(foreign_instances)"
  [ -n "$others" ] && err "Instância(s) FORA do script rodando (PID: $(echo "$others" | tr '\n' ' ')) — risco de resposta duplicada/errada!"
  return 0
}

cmd_logs() { tail -f "$LOG_FILE"; }

cmd_reindex() {
  require_env; resolve_java
  start_postgres; start_ollama
  # Sempre recompila: reindex com jar velho embeda o conjunto de docs ANTIGO e a versão
  # do nanoka não muda — o auto-reindex nunca corrige (aconteceu 2026-07-07).
  build || return 1
  local was_running=0; bot_running && was_running=1 && stop_bot
  step "Reindex da base HSR (HSR_REINDEX=true)"
  local rlog="$LOG_DIR/reindex.log"
  HSR_REINDEX=true nohup java -jar "$JAR" >"$rlog" 2>&1 &
  local rpid=$!
  echo "  ${C_DIM}acompanhe: tail -f $rlog${C_0}"
  # Um reindex leva MINUTOS (fetch do nanoka + embed de ~2k docs) — o wait_for genérico
  # de 30s matava o processo no meio do load. Espera dedicada: até 30 min, abortando
  # cedo se o processo morrer sozinho (crash/abort do ingester).
  local reindex_ok=1
  for _ in $(seq 1 1800); do
    grep -q "HSR reindex complete" "$rlog" 2>/dev/null && { reindex_ok=0; break; }
    kill -0 "$rpid" 2>/dev/null || break
    sleep 1
  done
  if [ "$reindex_ok" = 0 ]; then
    grep -E "profiles|relic|enemies|light cone|embedded batch .*/|Skipped|reindex complete" "$rlog" | tail -8 | sed 's/^/  /'
    ok "Reindex concluído"
  else err "Reindex não confirmou conclusão; veja $rlog"; fi
  kill "$rpid" 2>/dev/null; for _ in $(seq 1 10); do kill -0 "$rpid" 2>/dev/null || break; sleep 1; done
  kill -9 "$rpid" 2>/dev/null
  if [ "$was_running" = 1 ]; then warn "Bot estava rodando antes — reiniciando"; start_bot; fi
  # (o `[ ... ] &&` antigo fazia a função sair com código 1 quando o bot NÃO estava rodando)
}

case "${1:-}" in
  start)   cmd_start ;;
  restart) cmd_restart ;;
  stop)    shift; cmd_stop "${1:-}" ;;
  status)  cmd_status ;;
  logs)    cmd_logs ;;
  reindex) cmd_reindex ;;
  *) echo "uso: ./bot.sh {start|restart|stop [--all]|status|logs|reindex}"; exit 1 ;;
esac
