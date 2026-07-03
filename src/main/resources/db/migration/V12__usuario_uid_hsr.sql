-- UID de Honkai: Star Rail vinculado pelo próprio usuário via /uid. Usado pelo /build para
-- buscar a vitrine (showcase) do jogador na API pública do mihomo. Nulo até o usuário vincular.
ALTER TABLE usuario ADD COLUMN uid_hsr VARCHAR(16);
