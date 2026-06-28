package com.gcll.ticketagent.config;

import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

/**
 * PgVector 向量存储配置——默认向量后端。
 * <p>只要 {@link EmbeddingModel} bean 存在（DashScope autoconfig 提供）即激活，
 * 使向量语义检索成为默认 RAG 路径，而非依赖 milvus/pgvector profile 或退化到关键词检索。
 * <p>复用主数据源（与业务库同一个 PostgreSQL），无需单独配 vectorDataSource——
 * PgVector 扩展装在主库即可（{@code CREATE EXTENSION vector}）。
 * <p>Milvus 仍可通过 {@code @Profile("milvus")} 的 {@code MilvusVectorStoreConfig} 切换，作为可选后端。
 *
 * <p><b>测试环境关闭</b>：H2 不支持 pgvector 扩展语法（CREATE EXTENSION），
 * 测试 yml 设 {@code opsmind.vectorstore.pgvector.enabled=false} 关闭，避免上下文加载失败。
 */
@Configuration
@ConditionalOnProperty(prefix = "opsmind.vectorstore.pgvector", name = "enabled", havingValue = "true", matchIfMissing = true)
public class PgVectorStoreConfig {

    @Bean
    @ConditionalOnBean(EmbeddingModel.class)
    @ConditionalOnMissingBean(VectorStore.class)
    public VectorStore knowledgeVectorStore(DataSource dataSource, EmbeddingModel embeddingModel) {
        return PgVectorStore.builder(new JdbcTemplate(dataSource), embeddingModel)
                .initializeSchema(true)
                .build();
    }
}
