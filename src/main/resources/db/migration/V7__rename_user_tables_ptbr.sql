-- Renames the user memory tables and columns to Portuguese, matching the rest of the
-- domain language. Pure renames — no data is moved or dropped (the tables are created in V6;
-- this keeps Flyway history intact for environments where V6 was already applied).

ALTER TABLE user_profiles RENAME TO perfil_usuario;
ALTER TABLE perfil_usuario RENAME COLUMN user_id TO usuario_id;
ALTER TABLE perfil_usuario RENAME COLUMN memory_enabled TO memoria_ativa;
ALTER TABLE perfil_usuario RENAME COLUMN personality_summary TO resumo_personalidade;
ALTER TABLE perfil_usuario RENAME COLUMN exchanges_count TO contagem_interacoes;
ALTER TABLE perfil_usuario RENAME COLUMN created_at TO criado_em;
ALTER TABLE perfil_usuario RENAME COLUMN updated_at TO atualizado_em;
ALTER INDEX ux_user_profiles_user RENAME TO ux_perfil_usuario_usuario;

ALTER TABLE user_facts RENAME TO fato_usuario;
ALTER TABLE fato_usuario RENAME COLUMN user_id TO usuario_id;
ALTER TABLE fato_usuario RENAME COLUMN content TO conteudo;
ALTER TABLE fato_usuario RENAME COLUMN created_at TO criado_em;
ALTER INDEX ix_user_facts_user RENAME TO ix_fato_usuario_usuario;
