package com.yupi.yudada.config;

import com.yupi.yudada.service.AiExamGenerator;
import com.zhipu.oapi.ClientV4;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.dashscope.QwenChatModel;
import dev.langchain4j.service.AiServices;
import lombok.Data;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

@Configuration
@ConfigurationProperties(prefix = "ai")
@Data
public class AIConfig {

    /**
     * 智谱 AI 的 API Key (对应配置项 ai.apiKey)
     * 由 ConfigurationProperties 自动注入
     */
    private String apiKey;

    /**
     * 注入通义千问的 API Key (对应配置项 knowledge.qwen.api-key)
     * 注意：这里使用 @Value 单独注入，因为 prefix="ai" 覆盖不到 knowledge 开头的配置
     */
    @Value("${knowledge.qwen.api-key}")
    private String qwenApiKey;

    /**
     * 1. 智谱 AI 客户端 (保留原有逻辑，供旧业务使用)
     */
    @Bean
    public ClientV4 clientV4() {
        return new ClientV4.Builder(apiKey)
                .networkConfig(30, 60, 60, 60, TimeUnit.SECONDS)
                .build();
    }

    /**
     * 2. 通义千问 ChatModel (供 LangChain4j 新业务使用)
     * 专门用于知识库问答或流式生成
     */
    @Bean
    public ChatLanguageModel qwenChatModel() {
        return QwenChatModel.builder()
                .apiKey(qwenApiKey)
                .modelName("qwen-plus") // 推荐使用 qwen-plus 或 qwen-max
                .temperature(0.7f) // 修正 float 类型
                .topP(0.8)
                .enableSearch(true) // 可选：开启联网搜索增强
                .build();
    }
    /**
     * 3. AI 出题服务 (LangChain4j 核心魔法)
     * 这一步利用动态代理，将 AiExamGenerator 接口和 qwenChatModel 绑定，
     * 生成一个可直接调用的 Bean。
     */
    @Bean
    public AiExamGenerator aiExamGenerator(ChatLanguageModel qwenChatModel) {
        return AiServices.builder(AiExamGenerator.class)
                .chatLanguageModel(qwenChatModel)
                .chatMemory(MessageWindowChatMemory.withMaxMessages(10))
                .build();
    }
}