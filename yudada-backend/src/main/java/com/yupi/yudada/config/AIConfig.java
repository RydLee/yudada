package com.yupi.yudada.config;

import com.yupi.yudada.service.AiExamGenerator;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.dashscope.QwenChatModel;
import dev.langchain4j.service.AiServices;
import lombok.Data;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConfigurationProperties(prefix = "ai")
@Data
public class AIConfig {

    /**
     * 通义千问的 API Key (对应配置项 knowledge.qwen.api-key)
     */
    @Value("${knowledge.qwen.api-key}")
    private String qwenApiKey;

    /**
     * 通义千问 ChatModel (供 LangChain4j 使用)
     */
    @Bean
    public ChatLanguageModel qwenChatModel() {
        return QwenChatModel.builder()
                .apiKey(qwenApiKey)
                .modelName("qwen-plus")
                .temperature(0.7f)
                .topP(0.8)
                .enableSearch(true)
                .build();
    }

    /**
     * AI 出题服务 (LangChain4j 核心魔法)
     */
    @Bean
    public AiExamGenerator aiExamGenerator(ChatLanguageModel qwenChatModel) {
        return AiServices.builder(AiExamGenerator.class)
                .chatLanguageModel(qwenChatModel)
                .chatMemory(MessageWindowChatMemory.withMaxMessages(20))
                .build();
    }
}
