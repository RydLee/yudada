package com.yupi.yudada.controller;

import com.yupi.yudada.common.BaseResponse;
import com.yupi.yudada.common.ErrorCode;
import com.yupi.yudada.common.ResultUtils;
import com.yupi.yudada.exception.BusinessException;
import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.loader.FileSystemDocumentLoader;
import dev.langchain4j.data.document.parser.apache.tika.ApacheTikaDocumentParser;
import dev.langchain4j.data.document.splitter.DocumentSplitters;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.EmbeddingStoreIngestor;
import dev.langchain4j.data.segment.TextSegment;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import javax.annotation.Resource;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 知识库接口
 */
@RestController
@RequestMapping("/knowledge")
@Slf4j
public class KnowledgeBaseController {

    @Resource
    private EmbeddingModel embeddingModel;

    @Resource
    private EmbeddingStore<TextSegment> embeddingStore;

    /**
     * 上传 PDF 构建知识库
     *
     * @param file 前端上传的文件
     */
    @PostMapping("/add")
    public BaseResponse<String> addKnowledge(@RequestParam("file") MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "文件不能为空");
        }

        // 1. 将 MultipartFile 转存为本地临时文件 (Tika 解析需要文件路径或 InputStream)
        File tempFile = null;
        try {
            // 创建临时文件，保留原扩展名以帮助 Tika 识别文件类型
            String originalFilename = file.getOriginalFilename();
            String suffix = originalFilename.substring(originalFilename.lastIndexOf("."));
            Path tempPath = Files.createTempFile("kb_upload_", suffix);
            tempFile = tempPath.toFile();
            file.transferTo(tempFile);

            // 2. 加载文档
            Document document = FileSystemDocumentLoader.loadDocument(tempPath, new ApacheTikaDocumentParser());
            log.info("文件解析成功，字符数: {}", document.text().length());

            // 3. 构建入库管道 (切片 + 向量化 + 存储)
            EmbeddingStoreIngestor ingestor = EmbeddingStoreIngestor.builder()
                    .documentSplitter(DocumentSplitters.recursive(500, 0)) // 递归切分，每块500字
                    .embeddingModel(embeddingModel)
                    .embeddingStore(embeddingStore)
                    .build();

            // 4. 执行入库
            ingestor.ingest(document);
            log.info("文档向量化入库成功");

            return ResultUtils.success("上传并入库成功");

        } catch (Exception e) {
            log.error("知识库入库失败", e);
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "知识库处理失败: " + e.getMessage());
        } finally {
            // 5. 清理临时文件
            if (tempFile != null && tempFile.exists()) {
                boolean deleted = tempFile.delete();
                if (!deleted) {
                    log.warn("临时文件删除失败: {}", tempFile.getAbsolutePath());
                }
            }
        }
    }
}