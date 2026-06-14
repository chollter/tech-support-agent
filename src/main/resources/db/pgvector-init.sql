-- PostgreSQL + pgvector 初始化脚本
-- 用途：docker compose 首次启动 postgres 容器时自动执行
-- 向量表 vector_store 由 db/pgvector-init.sql 预建，应用启动时 PgVectorStore.initializeSchema(false)

CREATE EXTENSION IF NOT EXISTS vector;
CREATE EXTENSION IF NOT EXISTS hstore;
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";

-- 可选：手动预建表（需与 Embedding 维度一致，默认 1536）
-- 若启用下行，请将 PgVectorStoreConfig 中 initializeSchema 设为 false

CREATE TABLE IF NOT EXISTS vector_store (
    id uuid DEFAULT uuid_generate_v4() PRIMARY KEY,
    content text,
    metadata json,
    embedding vector(1536)
);
CREATE INDEX IF NOT EXISTS vector_store_embedding_idx
    ON vector_store USING HNSW (embedding vector_cosine_ops);
