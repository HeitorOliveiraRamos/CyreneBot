-- Habilita a extensão pgvector. O armazenamento vetorial em si (tabela `vector_store`
-- + índice HNSW) é criado pelo Spring AI quando `spring.ai.vectorstore.pgvector.initialize-schema=true`,
-- mas a extensão precisa existir antes — por isso fica aqui, sob controle do Flyway,
-- garantida antes de qualquer bean do vector store ser inicializado.
--
-- Esta é a tabela "com propósito explícito" prometida no comentário da V9: a base de
-- conhecimento (RAG) de Honkai: Star Rail vive em `vector_store`.
CREATE EXTENSION IF NOT EXISTS vector;
