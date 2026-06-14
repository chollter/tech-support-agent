package com.gcll.ticketagent.config;

import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

@Configuration
@Profile("pgvector")
@ConditionalOnBean(EmbeddingModel.class)
public class PgVectorStoreConfig {

    @Bean(name = "vectorDataSource")
    @ConfigurationProperties(prefix = "knowledge.vector.datasource")
    public DataSource vectorDataSource() {
        return DataSourceBuilder.create().build();
    }

    @Bean
    public VectorStore knowledgeVectorStore(
            @Qualifier("vectorDataSource") DataSource vectorDataSource,
            EmbeddingModel embeddingModel
    ) {
        return PgVectorStore.builder(new JdbcTemplate(vectorDataSource), embeddingModel)
                .initializeSchema(false)
                .build();
    }
}
