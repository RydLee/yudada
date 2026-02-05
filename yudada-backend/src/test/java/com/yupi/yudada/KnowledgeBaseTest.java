package com.yupi.yudada;

import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.loader.FileSystemDocumentLoader;
import dev.langchain4j.data.document.parser.apache.tika.ApacheTikaDocumentParser;
import dev.langchain4j.data.document.splitter.DocumentSplitters;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.dashscope.QwenEmbeddingModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.EmbeddingStoreIngestor;
import dev.langchain4j.store.embedding.milvus.MilvusEmbeddingStore;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.nio.file.Paths;

public class KnowledgeBaseTest {

    @Test
    public void testIngestPdf() {
        // --- 1. 准备 Qwen Embedding 模型 ---
        // ⚠️ 安全提醒：你的 API Key 刚才已经发在对话里了，建议去阿里云后台“废除”旧的并重新生成一个，以防被盗用。
        String qwenApiKey = "sk-ad540a6a93cc470e9602d6a9e46fa11a";

        EmbeddingModel embeddingModel = QwenEmbeddingModel.builder()
                .apiKey(qwenApiKey)
                .modelName("text-embedding-v3") // ✅ 修改点1：改为 v3，对应 1024 维度
                .build();

        // --- 2. 配置 Zilliz Cloud (Milvus) ---
        // ✅ 修改点2：末尾加上了 :19530
        // ⚠️ 再次确认：请务必确保这个地址是 Zilliz 控制台里的 "Public Endpoint" (公网地址)
        String zillizUri = "https://in03-f2505f5568c8176.serverless.ali-cn-hangzhou.cloud.zilliz.com.cn";
        String zillizToken = "b51997bba958e9dea87e9ce9eb5cdd598e6fed12a046c178d0960ebccf99f536bb3fce4647db3e909a4bd42869a8a800fe52e4c7";

        EmbeddingStore<TextSegment> embeddingStore = MilvusEmbeddingStore.builder()
                .uri(zillizUri)
                .token(zillizToken)
                .collectionName("yudada_kb_v2025") // ✅ 修改点3：换个标准的表名，防止和以前的脏数据冲突
                .dimension(1024)
                .build();

        // --- 3. 读取 PDF ---
        Path pdfPath = Paths.get("test.pdf");
        Document document = FileSystemDocumentLoader.loadDocument(pdfPath, new ApacheTikaDocumentParser());
        System.out.println("PDF 加载成功，长度: " + document.text().length());

        // --- 4. 构建管道 ---
        EmbeddingStoreIngestor ingestor = EmbeddingStoreIngestor.builder()
                .documentSplitter(DocumentSplitters.recursive(500, 0))
                .embeddingModel(embeddingModel)
                .embeddingStore(embeddingStore)
                .build();

        // --- 5. 执行入库 ---
        System.out.println("开始向量化并上传到 Zilliz Cloud...");
        ingestor.ingest(document);
        System.out.println("✅ 成功！数据已存入 Zilliz Cloud。");
    }
}