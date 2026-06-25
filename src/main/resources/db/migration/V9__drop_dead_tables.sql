-- Remove tabelas que não têm mais (ou nunca tiveram) leitor no código.
--
-- 1. mensagem_mencao: o caminho de resposta por @-menção gravava cada troca aqui e podava as
--    antigas, mas NADA lia esses dados de volta. O resumidor de personalidade que os consumia
--    foi removido na V8, então a tabela virou escrita-pura. O contexto da conversa agora vem
--    da cadeia de respostas do Discord ao vivo (ReplyChainResolver), não do banco.
--
-- 2. moderation_warning: criada na V1 e nunca mapeada por nenhuma entidade JPA nem referenciada
--    em código. Resquício do schema inicial.
--
-- Quando a persistência para RAG/fine-tuning chegar, uma tabela com propósito explícito deve
-- ser criada para ela, em vez de reaproveitar estes restos.

DROP TABLE IF EXISTS mensagem_mencao;
DROP TABLE IF EXISTS moderation_warning;
