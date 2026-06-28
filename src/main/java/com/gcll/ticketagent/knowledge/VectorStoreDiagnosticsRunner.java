package com.gcll.ticketagent.knowledge;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.Arrays;

@Component
public class VectorStoreDiagnosticsRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(VectorStoreDiagnosticsRunner.class);

    private final ListableBeanFactory beanFactory;
    private final Environment environment;

    public VectorStoreDiagnosticsRunner(ListableBeanFactory beanFactory, Environment environment) {
        this.beanFactory = beanFactory;
        this.environment = environment;
    }

    @Override
    public void run(ApplicationArguments args) {
        String[] embeddingBeans = beanFactory.getBeanNamesForType(EmbeddingModel.class);
        String[] vectorStoreBeans = beanFactory.getBeanNamesForType(VectorStore.class);

        log.info("Vector diagnostics: activeProfiles={}", Arrays.toString(environment.getActiveProfiles()));
        log.info("Vector diagnostics: EmbeddingModel beans count={}, names={}",
                embeddingBeans.length, Arrays.toString(embeddingBeans));
        log.info("Vector diagnostics: VectorStore beans count={}, names={}",
                vectorStoreBeans.length, Arrays.toString(vectorStoreBeans));

        if (embeddingBeans.length == 0) {
            log.warn("Vector diagnostics: no EmbeddingModel bean found. VectorStore config is gated by @ConditionalOnBean(EmbeddingModel.class).");
        }
        if (vectorStoreBeans.length == 0) {
            log.warn("Vector diagnostics: no VectorStore bean found. Check PgVectorStoreConfig/MilvusVectorStoreConfig conditions and initialization logs.");
        }
    }
}
