# Setup — máquina nova

O código vai todo pro git, mas algumas coisas são de **ambiente** e não são versionadas
(de propósito): o token, a extensão pgvector do Postgres, os embeddings (que ficam no banco)
e os modelos do Ollama. Este é o passo-a-passo pra deixar o bot funcional do zero.

## 1. Pré-requisitos

| Ferramenta | Por quê | Instalar (macOS / Homebrew) |
|---|---|---|
| **JDK 21** | Kotlin 2.1.0 quebra em JDK mais novo | `brew install --cask corretto@21` |
| **Maven** | build | `brew install maven` |
| **PostgreSQL 14+** | banco + vector store | `brew install postgresql@14 && brew services start postgresql@14` |
| **pgvector** | extensão de vetores (passo 3) | ver abaixo |
| **Ollama** | LLM + embeddings | `brew install ollama && brew services start ollama` |
| **Docker (colima)** | só pro web search (SearXNG) | `brew install colima docker docker-compose` |

## 2. Banco e usuário

```bash
createdb cyrene
psql -d cyrene -c "CREATE USER cyrene WITH PASSWORD 'cyrene'; GRANT ALL ON DATABASE cyrene TO cyrene;"
```
As migrations (Flyway) rodam sozinhas no primeiro boot.

## 3. pgvector (o pulo do gato)

A extensão é do **servidor de banco**, não vai no git. E ela **não é "trusted"**, então o
usuário da app (`cyrene`) não consegue criá-la — precisa de superuser uma vez.

```bash
# Build contra o postgresql@14 (garante a pasta certa de extensão):
git clone --branch v0.8.3 --depth 1 https://github.com/pgvector/pgvector.git /tmp/pgvector
cd /tmp/pgvector
PG_CONFIG=/opt/homebrew/opt/postgresql@14/bin/pg_config make && make install

# Cria a extensão como SUPERUSER (o usuário do SO costuma ser superuser no brew):
psql -d cyrene -c "CREATE EXTENSION IF NOT EXISTS vector;"
```
> Em distros Linux dá pra usar o pacote (`apt install postgresql-16-pgvector`) no lugar do build.

## 4. Modelos do Ollama

```bash
ollama pull nomic-embed-text          # embeddings (768-dim)
ollama pull qwen2.5:14b-instruct-q4_K_M   # chat (precisa ser tool-capable)
```

## 5. `.env`

```bash
cp .env.example .env
# edite: BOT_TOKEN, DB_*, MODEL_NAME/BRAIN/VOICE = seu modelo de chat
```

## 6. Construir a base de conhecimento (uma vez)

Os dados vêm do nanoka.cc (JSON estruturado, baixado na hora) e os **embeddings ficam no
Postgres**, então toda DB nova — ou cada novo patch — precisa rodar o reindex uma vez:

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 21)
set -a; source .env; set +a
HSR_REINDEX=true mvn spring-boot:run     # espere "HSR reindex complete", Ctrl-C
```
> A versão do patch é descoberta automaticamente na home do nanoka; fixe com
> `HSR_NANOKA_VERSION=x.y.z` se quiser travar um patch.

## 7. Web search (opcional — SearXNG)

```bash
colima start --cpu 2 --memory 4
cd docker/searxng && docker-compose up -d   # sobe na porta 8888
```
Garanta `SEARXNG_URL=http://localhost:8888` no `.env`. Sem isso, o `searchWeb` fica desligado
e o bot responde só com a base local (sem erro).
> Porta 8888 e não 8080 porque o Payara/GlassFish do NetBeans costuma ocupar a 8080.

## 8. Rodar

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 21)
set -a; source .env; set +a
mvn spring-boot:run     # SEM HSR_REINDEX
```

---

### Resumo do que NÃO vai no git
- `.env` (token) → recriar do `.env.example`
- extensão pgvector + `CREATE EXTENSION` → passo 3, em cada DB nova
- conteúdo de `vector_store` (embeddings) → passo 6, em cada DB nova
- modelos do Ollama → passo 4
- VM do colima + container SearXNG → passo 7
