package com.gcll.ticketagent.config;

import io.milvus.client.MilvusServiceClient;
import io.milvus.param.ConnectParam;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.milvus.MilvusVectorStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import java.util.concurrent.TimeUnit;

@Configuration
@Profile("milvus")
@ConditionalOnBean(EmbeddingModel.class)
public class MilvusVectorStoreConfig {

    @Bean
    @ConfigurationProperties(prefix = "spring.ai.vectorstore.milvus")
    public MilvusVectorStoreProperties milvusVectorStoreProperties() {
        return new MilvusVectorStoreProperties();
    }

    @Bean(destroyMethod = "")
    public MilvusServiceClient milvusServiceClient(MilvusVectorStoreProperties properties) {
        ConnectParam.Builder builder = ConnectParam.newBuilder()
                .withHost(properties.getClient().getHost())
                .withPort(properties.getClient().getPort())
                .withDatabaseName(properties.getDatabaseName())
                .withConnectTimeout(properties.getClient().getConnectTimeoutSeconds(), TimeUnit.SECONDS)
                .withRpcDeadline(properties.getClient().getRpcDeadlineSeconds(), TimeUnit.SECONDS)
                .secure(properties.getClient().isSecure());

        if (!isBlank(properties.getClient().getToken())) {
            builder.withToken(properties.getClient().getToken());
        } else if (!isBlank(properties.getClient().getUsername())) {
            builder.withAuthorization(properties.getClient().getUsername(), properties.getClient().getPassword());
        }

        return new MilvusServiceClient(builder.build());
    }

    @Bean
    public VectorStore vectorStore(
            MilvusServiceClient milvusServiceClient,
            EmbeddingModel embeddingModel,
            MilvusVectorStoreProperties properties
    ) {
        return MilvusVectorStore.builder(milvusServiceClient, embeddingModel)
                .databaseName(properties.getDatabaseName())
                .collectionName(properties.getCollectionName())
                .embeddingDimension(properties.getEmbeddingDimension())
                .initializeSchema(properties.isInitializeSchema())
                .build();
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    public static class MilvusVectorStoreProperties {
        private Client client = new Client();
        private String databaseName = "default";
        private String collectionName = "knowledge_chunk_vectors";
        private int embeddingDimension = 1536;
        private boolean initializeSchema = true;

        public Client getClient() {
            return client;
        }

        public void setClient(Client client) {
            this.client = client;
        }

        public String getDatabaseName() {
            return databaseName;
        }

        public void setDatabaseName(String databaseName) {
            this.databaseName = databaseName;
        }

        public String getCollectionName() {
            return collectionName;
        }

        public void setCollectionName(String collectionName) {
            this.collectionName = collectionName;
        }

        public int getEmbeddingDimension() {
            return embeddingDimension;
        }

        public void setEmbeddingDimension(int embeddingDimension) {
            this.embeddingDimension = embeddingDimension;
        }

        public boolean isInitializeSchema() {
            return initializeSchema;
        }

        public void setInitializeSchema(boolean initializeSchema) {
            this.initializeSchema = initializeSchema;
        }
    }

    public static class Client {
        private String host = "localhost";
        private int port = 19530;
        private String username;
        private String password;
        private String token;
        private boolean secure;
        private long connectTimeoutSeconds = 10;
        private long rpcDeadlineSeconds = 30;

        public String getHost() {
            return host;
        }

        public void setHost(String host) {
            this.host = host;
        }

        public int getPort() {
            return port;
        }

        public void setPort(int port) {
            this.port = port;
        }

        public String getUsername() {
            return username;
        }

        public void setUsername(String username) {
            this.username = username;
        }

        public String getPassword() {
            return password;
        }

        public void setPassword(String password) {
            this.password = password;
        }

        public String getToken() {
            return token;
        }

        public void setToken(String token) {
            this.token = token;
        }

        public boolean isSecure() {
            return secure;
        }

        public void setSecure(boolean secure) {
            this.secure = secure;
        }

        public long getConnectTimeoutSeconds() {
            return connectTimeoutSeconds;
        }

        public void setConnectTimeoutSeconds(long connectTimeoutSeconds) {
            this.connectTimeoutSeconds = connectTimeoutSeconds;
        }

        public long getRpcDeadlineSeconds() {
            return rpcDeadlineSeconds;
        }

        public void setRpcDeadlineSeconds(long rpcDeadlineSeconds) {
            this.rpcDeadlineSeconds = rpcDeadlineSeconds;
        }
    }
}
