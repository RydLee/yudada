package com.yupi.yudada;

import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.dashscope.QwenChatModel;
import dev.langchain4j.model.dashscope.QwenEmbeddingModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.content.retriever.EmbeddingStoreContentRetriever;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.milvus.MilvusEmbeddingStore;
import org.junit.jupiter.api.Test;

public class ResumeInterviewTest {

    // 定义一个 AI 服务接口，LangChain4j 会自动实现它
    interface Interviewer {
        String ask(String userQuery);
    }

    @Test
    public void generateQuestionsFromResume() {
        // --- 1. 配置组件 ---

        // A. 聊天模型 (用于生成问题)
        // 建议使用 qwen-plus 或 qwen-max，效果比 turbo 好很多
        String apiKey = "sk-ad540a6a93cc470e9602d6a9e46fa11a";
        ChatLanguageModel chatModel = QwenChatModel.builder()
                .apiKey(apiKey)
                .modelName("qwen-plus")
                .build();

        // B. 向量模型 (用于检索，必须和存入时一致！)
        EmbeddingModel embeddingModel = QwenEmbeddingModel.builder()
                .apiKey(apiKey)
                .modelName("text-embedding-v3") // 必须是 v3 (1024维度)
                .build();
        String zillizUri = "https://in03-f2505f5568c8176.serverless.ali-cn-hangzhou.cloud.zilliz.com.cn";
        String zillizToken = "b51997bba958e9dea87e9ce9eb5cdd598e6fed12a046c178d0960ebccf99f536bb3fce4647db3e909a4bd42869a8a800fe52e4c7";

        // C. 向量数据库 (数据源)
        EmbeddingStore<TextSegment> embeddingStore = MilvusEmbeddingStore.builder()
                .uri(zillizUri)
                .token(zillizToken)
                .collectionName("yudada_kb_v2025") // ⚠️ 一定要和存入时的表名一致
                .dimension(1024)
                .build();

        // --- 2. 构建检索器 (Retriever) ---
        // 它的作用是：当用户提问时，自动去 Milvus 查相关资料
        ContentRetriever retriever = EmbeddingStoreContentRetriever.builder()
                .embeddingStore(embeddingStore)
                .embeddingModel(embeddingModel)
                .maxResults(3) // 检索最相关的 3 段简历内容
                .minScore(0.6) // 相似度阈值，太低的内容不要
                .build();

        // --- 3. 构建 AI 服务 ---
        Interviewer interviewer = AiServices.builder(Interviewer.class)
                .chatLanguageModel(chatModel)
                .contentRetriever(retriever)
                .chatMemory(MessageWindowChatMemory.withMaxMessages(10)) // 开启记忆
                .build();

        // --- 4. 开始提问 ---
        // 这里的 prompt 只是指令，AI 会自动带上从数据库查到的简历内容
        String prompt = "我的简历中提到了哪些核心技能？请根据这些技能，生成 3 道中高级面试题，并给出简要的参考答案。";

        System.out.println("🤖 正在分析简历并生成面试题...\n");
        String response = interviewer.ask(prompt);

        System.out.println("====== 面试题生成结果 ======");
        System.out.println(response);
    }
}

/*
        ### 代码原理解析

1.  **`QwenChatModel`**: 之前我们只用了 Embedding 模型（转向量），现在引入了 Chat 模型（用来“说话”）。这里推荐 `qwen-plus`，逻辑能力更强。
        2.  **`EmbeddingStoreContentRetriever`**: 这是一个胶水组件。当你问“生成面试题”时，它会先把这句话转向量，去 Milvus 里把你的简历片段捞出来。
        3.  **`AiServices`**: 这是 LangChain4j 最强大的功能。它把“捞出来的数据”和“你的问题”拼在一起，发给 Qwen，让 Qwen **“基于（检索到的）简历内容回答”**。

        ### 运行结果预期

控制台会输出类似这样的内容：

🤖 正在分析简历并生成面试题...

        ====== 面试题生成结果 ======
基于您简历中提到的 Java、Spring Boot 和微服务经验，为您生成以下面试题：

        1. 问题：在您的项目中，是如何解决分布式事务问题的？
参考答案：...

        2. 问题：您提到了使用 Redis 缓存，请问遇到过缓存穿透吗？怎么解决的？
参考答案：...
        ...

 */