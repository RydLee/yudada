package com.yupi.yudada;

import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.StreamingChatLanguageModel; // 1. 引入流式接口
import dev.langchain4j.model.dashscope.QwenEmbeddingModel;
import dev.langchain4j.model.dashscope.QwenStreamingChatModel; // 2. 引入 Qwen 流式模型
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.content.retriever.EmbeddingStoreContentRetriever;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.TokenStream; // 3. 引入 Token流
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.milvus.MilvusEmbeddingStore;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;

public class ResumeStreamInterviewTest {

    // --- 改动点 1：接口返回值改为 TokenStream ---
    interface StreamInterviewer {
        TokenStream ask(String userQuery);
    }

    @Test
    public void testStreamInterview() {
        String apiKey = "sk-ad540a6a93cc470e9602d6a9e46fa11a";

        // --- 改动点 2：使用 QwenStreamingChatModel ---
        StreamingChatLanguageModel streamingChatModel = QwenStreamingChatModel.builder()
                .apiKey(apiKey)
                .modelName("qwen-plus") // 依然推荐 plus 或 max
                .build();

        // 向量模型 (保持不变)
        EmbeddingModel embeddingModel = QwenEmbeddingModel.builder()
                .apiKey(apiKey)
                .modelName("text-embedding-v3")
                .build();

        // 向量库 (保持不变，注意 timeout)
        EmbeddingStore<TextSegment> embeddingStore = MilvusEmbeddingStore.builder()
                .uri("https://in03-f2505f5568c8176.serverless.ali-cn-hangzhou.cloud.zilliz.com.cn")
                .token("b51997bba958e9dea87e9ce9eb5cdd598e6fed12a046c178d0960ebccf99f536bb3fce4647db3e909a4bd42869a8a800fe52e4c7")
                .collectionName("yudada_kb_v2025")
                .dimension(1024)
                // ⚠️ LangChain4j 默认字段名就是 id, text, embedding。
                // 如果 builder 中没有这些方法，直接省略即可，不需要显式配置。
                .build();

        // 检索器 (保持不变)
        ContentRetriever retriever = EmbeddingStoreContentRetriever.builder()
                .embeddingStore(embeddingStore)
                .embeddingModel(embeddingModel)
                .maxResults(3)
                .minScore(0.6)
                .build();

        // --- 改动点 3：构建 Service 时使用 streamingChatLanguageModel ---
        StreamInterviewer interviewer = AiServices.builder(StreamInterviewer.class)
                .streamingChatLanguageModel(streamingChatModel) // 注意这里方法名变了
                .contentRetriever(retriever)
                .chatMemory(MessageWindowChatMemory.withMaxMessages(10))
                .build();

        String prompt = "我的简历中提到了哪些核心技能？请根据这些技能，生成 3 道中高级面试题。";

        System.out.println("🤖 正在思考中 (Stream模式)...");

        // --- 改动点 4：调用流式接口并处理回调 ---
        // 在单元测试中，因为主线程结束后会立刻杀掉所有异步线程，所以我们需要用 Future 来阻塞等待
        CompletableFuture<Void> future = new CompletableFuture<>();

        interviewer.ask(prompt)
                .onNext(token -> {
                    // 这里是核心：每收到一个字（token），就打印出来
                    // 在 Web 项目中，这里通常是写入 SSE (Server-Sent Events) 或 WebSocket
                    System.out.print(token);
                })
                .onComplete(response -> {
                    // 生成结束
                    System.out.println("\n\n✅ 生成完毕！");
                    future.complete(null); // 通知主线程可以结束了
                })
                .onError(error -> {
                    // 处理报错
                    error.printStackTrace();
                    future.completeExceptionally(error);
                })
                .start(); // ⚠️ 别忘了调用 start()

        // 阻塞主线程，直到流式生成结束
        future.join();
    }
}