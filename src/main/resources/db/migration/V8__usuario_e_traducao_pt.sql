-- Consolida toda a memória de usuário numa única tabela `usuario` e traduz o restante do
-- schema para português, alinhando o banco com a linguagem do domínio.
--
-- A memória passa a ser um texto livre fornecido pelo próprio usuário via /memoria (não há
-- mais resumo de personalidade gerado por IA, nem fatos salvos pela LLM). Por isso as tabelas
-- antigas de memória são descartadas — os dados não são migrados.

-- 1. Remove as tabelas antigas de memória de usuário (dados descartáveis).
DROP TABLE IF EXISTS fato_usuario;
DROP TABLE IF EXISTS perfil_usuario;
DROP TABLE IF EXISTS users_info;

-- 2. Uma linha global por usuário: nome efetivo + o texto livre que o usuário pede para lembrar.
CREATE TABLE usuario (
    id            BIGSERIAL    PRIMARY KEY,
    usuario_id    VARCHAR(64)  NOT NULL,
    nome          VARCHAR(128) NOT NULL,
    memoria       TEXT,
    criado_em     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    atualizado_em TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX ux_usuario_usuario_id ON usuario (usuario_id);

-- 3. conversation -> conversa
ALTER TABLE conversation RENAME TO conversa;
ALTER TABLE conversa RENAME COLUMN user_id TO usuario_id;
ALTER TABLE conversa RENAME COLUMN channel_id TO canal_id;
ALTER TABLE conversa RENAME COLUMN active TO ativa;
ALTER TABLE conversa RENAME COLUMN started_at TO iniciada_em;
ALTER TABLE conversa RENAME COLUMN ended_at TO encerrada_em;
ALTER INDEX ux_conversation_active_user_channel RENAME TO ux_conversa_ativa_usuario_canal;
ALTER INDEX ix_conversation_user_channel RENAME TO ix_conversa_usuario_canal;

-- 4. conversation_exchange -> troca_conversa
ALTER TABLE conversation_exchange RENAME TO troca_conversa;
ALTER TABLE troca_conversa RENAME COLUMN conversation_id TO conversa_id;
ALTER TABLE troca_conversa RENAME COLUMN user_message TO mensagem_usuario;
ALTER TABLE troca_conversa RENAME COLUMN assistant_reply TO resposta_assistente;
ALTER TABLE troca_conversa RENAME COLUMN created_at TO criado_em;
ALTER INDEX ix_conversation_exchange_conv RENAME TO ix_troca_conversa_conversa;

-- 5. mention_message -> mensagem_mencao
ALTER TABLE mention_message RENAME TO mensagem_mencao;
ALTER TABLE mensagem_mencao RENAME COLUMN user_id TO usuario_id;
ALTER TABLE mensagem_mencao RENAME COLUMN guild_id TO servidor_id;
ALTER TABLE mensagem_mencao RENAME COLUMN channel_id TO canal_id;
ALTER TABLE mensagem_mencao RENAME COLUMN user_message TO mensagem_usuario;
ALTER TABLE mensagem_mencao RENAME COLUMN assistant_reply TO resposta_assistente;
ALTER TABLE mensagem_mencao RENAME COLUMN created_at TO criado_em;
ALTER INDEX ix_mention_message_user_guild_created RENAME TO ix_mensagem_mencao_usuario_servidor_criado;
ALTER INDEX ix_mention_message_user_channel_created RENAME TO ix_mensagem_mencao_usuario_canal_criado;
