-- Advertências emitidas por moderadores via /avisar, lidas de volta por /avisos. Existem
-- para dar HISTÓRICO antes de alguém partir pro /mutar ou /banir: um usuário com três avisos
-- é um caso diferente de um usuário no primeiro tropeço, e sem registro isso se perde no chat.
--
-- Escopo por SERVIDOR (guild_id + usuario_id), diferente da tabela `usuario`, que é global:
-- um aviso dado num servidor não é da conta de outro.
--
-- Nada é apagado automaticamente — o histórico só vale se for completo. moderador_id fica
-- guardado pra auditoria (quem avisou quem).
CREATE TABLE aviso (
    id           BIGSERIAL PRIMARY KEY,
    guild_id     TEXT        NOT NULL,
    usuario_id   TEXT        NOT NULL,
    moderador_id TEXT        NOT NULL,
    motivo       TEXT        NOT NULL,
    criado_em    TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- A única consulta que existe: os avisos de uma pessoa num servidor, mais recentes primeiro.
CREATE INDEX aviso_guild_usuario_idx ON aviso (guild_id, usuario_id, criado_em DESC);
