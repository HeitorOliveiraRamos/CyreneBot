# Setup — máquina nova

O código vai todo pro git, mas algumas coisas são de **ambiente** e não são versionadas
(de propósito): o token, a extensão pgvector do Postgres, os embeddings (que ficam no banco)
e os modelos do Ollama. Este é o passo-a-passo pra deixar o bot funcional do zero.

Funciona em macOS e Linux. O `./bot.sh` cuida de subir/parar tudo nos dois.

## 1. Pré-requisitos

| Ferramenta | Por quê | macOS (Homebrew) | Linux (Debian/Ubuntu) |
|---|---|---|---|
| **JDK 21** | Kotlin 2.1.0 quebra em JDK mais novo | `brew install --cask corretto@21` | `apt install openjdk-21-jdk` |
| **Maven** | build | `brew install maven` | `apt install maven` |
| **PostgreSQL 14+** | banco + vector store | `brew install postgresql@14 && brew services start postgresql@14` | `apt install postgresql` |
| **pgvector** | extensão de vetores (passo 3) | ver abaixo | `apt install postgresql-<versão>-pgvector` |
| **Ollama** | LLM + embeddings | `brew install ollama && brew services start ollama` | `curl -fsSL https://ollama.com/install.sh \| sh` |
| **Docker** | só pro web search (SearXNG) | `brew install colima docker docker-compose` | `apt install docker.io docker-compose-v2` (nativo, sem colima) |

## 2. Banco e usuário

```bash
createdb cyrene
psql -d cyrene -c "CREATE USER cyrene WITH PASSWORD 'cyrene'; GRANT ALL ON DATABASE cyrene TO cyrene;"
```
> No Linux o seu usuário do SO não é superuser do Postgres por padrão — prefixe os dois
> comandos com `sudo -u postgres`.

As migrations (Flyway) rodam sozinhas no primeiro boot.

## 3. pgvector (o pulo do gato)

A extensão é do **servidor de banco**, não vai no git. E ela **não é "trusted"**, então o
usuário da app (`cyrene`) não consegue criá-la — precisa de superuser uma vez.

**Linux:** use o pacote da distro (ex.: `apt install postgresql-16-pgvector`, casando com a
versão do servidor) e pule direto pro `CREATE EXTENSION`.

**macOS (brew):** build contra o postgresql@14 (garante a pasta certa de extensão):
```bash
git clone --branch v0.8.3 --depth 1 https://github.com/pgvector/pgvector.git /tmp/pgvector
cd /tmp/pgvector
PG_CONFIG=/opt/homebrew/opt/postgresql@14/bin/pg_config make && make install
```

Depois, nos dois casos, cria a extensão como **superuser** (no Linux: `sudo -u postgres psql ...`):
```bash
psql -d cyrene -c "CREATE EXTENSION IF NOT EXISTS vector;"
```

## 4. Modelos do Ollama

```bash
ollama pull nomic-embed-text                # embeddings (768-dim)
ollama pull qwen2.5:14b-instruct-q4_K_M     # brain/chat (precisa ser tool-capable)
ollama pull qwen2.5:7b-instruct-q4_K_M      # voice (persona) — default de VOICE_MODEL_NAME
```
> Visão é opcional: `ollama pull qwen2.5vl` e preencha `VISION_MODEL_NAME` no `.env`.
> Vazio = imagens ignoradas, sem erro.

## 5. `.env`

```bash
cp .env.example .env
# edite: BOT_TOKEN, DB_*, MODEL_NAME/BRAIN/VOICE = seus modelos de chat
```

## 6. Construir a base de conhecimento (uma vez)

Os dados vêm do nanoka.cc (JSON estruturado, baixado na hora) e os **embeddings ficam no
Postgres**, então toda DB nova — ou cada novo patch — precisa rodar o reindex uma vez:

```bash
./bot.sh reindex     # builda o jar, sobe Postgres/Ollama e roda o reindex até o fim
```
> A versão do patch é descoberta automaticamente na home do nanoka; fixe com
> `HSR_NANOKA_VERSION=x.y.z` no `.env` se quiser travar um patch.

## 7. Web search (opcional — SearXNG)

O `./bot.sh start` já sobe o SearXNG sozinho quando `SEARXNG_URL` está no `.env`
(default `http://localhost:8888`). Só garanta o Docker de pé:

- **macOS:** o script sobe o colima automaticamente.
- **Linux:** docker nativo — `sudo systemctl enable --now docker` (e seu usuário no grupo `docker`).

Sem `SEARXNG_URL`, o `searchWeb` fica desligado e o bot responde só com a base local (sem erro).
> Porta 8888 e não 8080 porque o Payara/GlassFish do NetBeans costuma ocupar a 8080.

## 8. Rodar

```bash
./bot.sh start      # sobe infra + bot em background (logs em logs/bot.log)
./bot.sh status     # o que está no ar
./bot.sh logs       # tail -f
./bot.sh restart    # recompila e reinicia só o bot (após mudar código)
./bot.sh stop       # para o bot; `stop --all` derruba o SearXNG também
```

<details>
<summary>Alternativa manual (sem bot.sh, roda em foreground)</summary>

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 21)   # macOS; no Linux aponte pro JDK 21
set -a; source .env; set +a
mvn spring-boot:run                                # reindex: prefixe com HSR_REINDEX=true
```
</details>

---

### Resumo do que NÃO vai no git
- `.env` (token) → recriar do `.env.example`
- extensão pgvector + `CREATE EXTENSION` → passo 3, em cada DB nova
- conteúdo de `vector_store` (embeddings) → passo 6, em cada DB nova
- modelos do Ollama → passo 4
- container SearXNG (e colima no mac) → passo 7
