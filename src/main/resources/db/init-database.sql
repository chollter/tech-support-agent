-- ============================================================================
-- 数据库初始化脚本（PostgreSQL + pgvector）
-- ----------------------------------------------------------------------------
-- 用途：在「应用首次启动前」由 DBA / 开发者手动执行一次，完成：
--   1. 创建应用数据库 tech_support_agent
--   2. 创建应用账号 postgres（与 application.yml 默认一致）并授权
--   3. 在该库内安装 pgvector 扩展（vector / hstore / uuid-ossp）
--   4. 将扩展的使用权限授予应用账号，使其能创建 vector 类型的表与索引
--
-- ⚠️ 前置条件：
--   - 必须用具备 superuser / createdb 权限的账号连接（默认 postgres superuser）
--   - 必须连接到一个「已存在」的库（通常是默认的 postgres 库）来执行 CREATE DATABASE
--     —— 即 psql -U postgres -d postgres -f init-database.sql
--   - 本脚本幂等：重复执行不会报错（IF NOT EXISTS / 已存在则跳过授权）
--
-- 执行位置：见仓库 README「数据库初始化」一节——这是所有自动脚本的「第 0 步」。
-- ============================================================================

-- ---------------------------------------------------------------------------
-- 第 1 步：创建应用数据库
--   - 名称与 application.yml 的 ${PG_DATABASE:tech_support_agent} 默认值一致
--   - 先断开可能占用该库的连接，避免 ALTER/OWNER 时被阻塞（开发环境常踩）
-- ---------------------------------------------------------------------------
SELECT pg_terminate_backend(pid)
FROM pg_stat_activity
WHERE datname = 'tech_support_agent' AND pid <> pg_backend_pid();

CREATE DATABASE tech_support_agent
    WITH ENCODING 'UTF8'
    LC_COLLATE 'C.UTF-8'
    LC_CTYPE 'C.UTF-8'
    TEMPLATE template0;

-- ---------------------------------------------------------------------------
-- 第 2 步：创建/确认应用账号
--   - 用户名/密码与 application.yml 的 ${PG_USER:postgres} / ${PG_PASSWORD:postgres} 默认值一致
--   - 生产环境请用强密码并通过环境变量覆盖 PG_PASSWORD，不要把明文密码提交进版本库
-- ---------------------------------------------------------------------------
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'postgres') THEN
        CREATE ROLE postgres WITH LOGIN PASSWORD 'postgres' CREATEDB;
    END IF;
END$$;

-- ---------------------------------------------------------------------------
-- 第 3 步：在「应用库内」安装 pgvector 扩展
--   - \c 切换到 tech_support_agent 库后续语句都在该库内执行
--   - vector：向量类型与相似度算子（PgVector 依赖）
--   - hstore / uuid-ossp：pgvector-init.sql 预建 vector_store 时用到
--     （当前 PgVectorStoreConfig.initializeSchema=true 由应用自动建表，
--      但保留这两个扩展以兼容预建脚本路径，避免切换时缺扩展）
-- ---------------------------------------------------------------------------
\connect tech_support_agent

CREATE EXTENSION IF NOT EXISTS vector;
CREATE EXTENSION IF NOT EXISTS hstore;
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";

-- ---------------------------------------------------------------------------
-- 第 4 步：授予应用账号在该库的权限
--   - 必须授 USAGE 于 schema public，否则应用账号无法在 public 下建表
--   - 授 CREATE 让应用首次启动时 PgVectorStore 能自动建 vector_store 表
--   - vector 类型的 USAGE 权限：让应用账号能定义 vector 列与索引
-- ---------------------------------------------------------------------------
GRANT USAGE ON SCHEMA public TO postgres;
GRANT CREATE ON SCHEMA public TO postgres;

-- 应用账号对扩展函数/类型的 USAGE（vector 类型本身）
GRANT USAGE, SELECT ON ALL TABLES IN SCHEMA public TO postgres;
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT USAGE, SELECT ON TABLES TO postgres;

-- ---------------------------------------------------------------------------
-- 第 5 步（可选）：把库 owner 设为应用账号
--   - 若应用账号不是创建库的 superuser，确保它拥有库级权限
--   - postgres 账号通常已是 superuser，此步可省略，但保留以保证一致性
-- ---------------------------------------------------------------------------
ALTER DATABASE tech_support_agent OWNER TO postgres;

-- ============================================================================
-- 至此库已就绪。后续步骤（全部由应用启动时自动完成，无需手动）：
--   第 6 步：db/schema.sql        —— 建业务表（agent_run / agent_step / ...）
--   第 7 步：db/data-knowledge.sql —— 灌知识库种子数据
--   第 8 步：PgVectorStoreConfig  —— 应用自动建 vector_store 表（initializeSchema=true）
-- ============================================================================
