package com.yupi.yudada.config;

import dev.langchain4j.model.dashscope.QwenEmbeddingModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.milvus.MilvusEmbeddingStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 知识库配置 (Embedding Model + Vector Store)
 */
@Configuration
public class KnowledgeBaseConfig {

    @Value("${knowledge.qwen.api-key}")
    private String qwenApiKey;

    @Value("${knowledge.milvus.uri}")
    private String milvusUri;

    @Value("${knowledge.milvus.token}")
    private String milvusToken;

    @Value("${knowledge.milvus.collection-name}")
    private String collectionName;

    /**
     * 1. 初始化 Qwen Embedding 模型
     */
    @Bean
    public EmbeddingModel embeddingModel() {
        return QwenEmbeddingModel.builder()
                .apiKey(qwenApiKey)
                .modelName("text-embedding-v3") // 维度 1024
                .build();
    }

    /**
     * 2. 初始化 Milvus 向量存储
     */
    @Bean
    public EmbeddingStore<TextSegment> embeddingStore() {
        return MilvusEmbeddingStore.builder()
                .uri(milvusUri)
                .token(milvusToken)
                .collectionName(collectionName)
                .dimension(1024) // 必须与 Embedding 模型维度一致
                .build();
    }
}