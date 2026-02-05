package com.yupi.yudada.controller;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.yupi.yudada.common.BaseResponse;
import com.yupi.yudada.common.ErrorCode;
import com.yupi.yudada.common.ResultUtils;
import com.yupi.yudada.exception.BusinessException;
import com.yupi.yudada.model.dto.question.QuestionContentDTO; // 引入你提供的类
import com.yupi.yudada.service.AiExamGenerator;
import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.loader.FileSystemDocumentLoader;
import dev.langchain4j.data.document.parser.apache.tika.ApacheTikaDocumentParser;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import javax.annotation.Resource;
import java.io.File;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/exam")
@Slf4j
public class ExamController {

    @Resource
    private AiExamGenerator aiExamGenerator;

    private final Gson gson = new Gson();

    /**
     * 上传 PDF 生成试卷
     */
    @PostMapping("/generate")
    public BaseResponse<List<QuestionContentDTO>> generateExam(@RequestParam("file") MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "文件不能为空");
        }

        File tempFile = null;
        try {
            // 1. 保存临时文件
            String originalFilename = file.getOriginalFilename();
            String suffix = (originalFilename != null && originalFilename.contains("."))
                    ? originalFilename.substring(originalFilename.lastIndexOf("."))
                    : ".pdf";

            Path tempPath = Files.createTempFile("exam_upload_", suffix);
            tempFile = tempPath.toFile();
            file.transferTo(tempFile);

            // 2. 解析文档
            Document document = FileSystemDocumentLoader.loadDocument(tempPath, new ApacheTikaDocumentParser());
            String fullText = document.text();

            // 3. 截断保护
            if (fullText.length() > 20000) {
                fullText = fullText.substring(0, 20000);
            }

            // 4. 调用 AI 生成 (确保 AiExamGenerator 接口返回的是 String)
            String jsonResult = aiExamGenerator.generateQuestions(fullText);
            log.info("AI 原始返回: {}", jsonResult);

            // 5. 解析并转换为 QuestionContentDTO
            List<QuestionContentDTO> questions = parseAiResponse(jsonResult);

            return ResultUtils.success(questions);

        } catch (Exception e) {
            log.error("生成试卷失败", e);
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "生成试卷失败: " + e.getMessage());
        } finally {
            // 6. 清理临时文件
            if (tempFile != null && tempFile.exists()) {
                boolean deleted = tempFile.delete();
                if (!deleted) {
                    log.warn("临时文件删除失败: {}", tempFile.getAbsolutePath());
                }
            }
        }
    }

    /**
     * 解析 AI 返回内容，并转换为标准的 QuestionContentDTO
     */
    private List<QuestionContentDTO> parseAiResponse(String jsonContent) {
        try {
            // 1. 提取 JSON 数组
            int start = jsonContent.indexOf("[");
            int end = jsonContent.lastIndexOf("]");

            if (start == -1 || end == -1) {
                throw new BusinessException(ErrorCode.SYSTEM_ERROR, "AI 返回格式异常，未检测到 JSON 数组");
            }

            String cleanJson = jsonContent.substring(start, end + 1);

            // 2. 先用临时类接住 AI 的原始数据 (question, A, B, score)
            Type listType = new TypeToken<List<AiRawQuestionDTO>>() {}.getType();
            List<AiRawQuestionDTO> rawList = gson.fromJson(cleanJson, listType);

            // 3. 转换成 QuestionContentDTO
            List<QuestionContentDTO> resultList = new ArrayList<>();
            if (rawList != null) {
                for (AiRawQuestionDTO raw : rawList) {
                    QuestionContentDTO dto = new QuestionContentDTO();
                    dto.setTitle(raw.getQuestion()); // 映射标题

                    List<QuestionContentDTO.Option> options = new ArrayList<>();

                    // --- 映射选项 A ---
                    if (raw.getA() != null) {
                        QuestionContentDTO.Option optionA = new QuestionContentDTO.Option();
                        optionA.setKey("A");
                        optionA.setValue(raw.getA());
                        // 安全获取分数
                        int score = (raw.getScore() != null && raw.getScore().containsKey("A"))
                                ? raw.getScore().get("A") : 0;
                        optionA.setScore(score);
                        options.add(optionA);
                    }

                    // --- 映射选项 B ---
                    if (raw.getB() != null) {
                        QuestionContentDTO.Option optionB = new QuestionContentDTO.Option();
                        optionB.setKey("B");
                        optionB.setValue(raw.getB());
                        // 安全获取分数
                        int score = (raw.getScore() != null && raw.getScore().containsKey("B"))
                                ? raw.getScore().get("B") : 0;
                        optionB.setScore(score);
                        options.add(optionB);
                    }

                    dto.setOptions(options);
                    resultList.add(dto);
                }
            }
            return resultList;

        } catch (Exception e) {
            log.error("JSON 解析失败, content: {}", jsonContent, e);
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "AI 生成题目解析失败");
        }
    }

    /**
     * 临时内部类：专门用于接收 AI 的原始 JSON 格式
     * AI 返回结构: {"question": "...", "A": "...", "B": "...", "score": {"A":1, "B":0}}
     * 注意：这个类仅在 Controller 内部使用，用于数据清洗
     */
    @Data
    @NoArgsConstructor
    static class AiRawQuestionDTO {
        private String question;
        private String A;
        private String B;
        private Map<String, Integer> score;
    }
}