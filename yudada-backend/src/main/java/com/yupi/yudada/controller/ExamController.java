package com.yupi.yudada.controller;

import cn.hutool.core.util.IdUtil;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonParser;
import com.google.gson.reflect.TypeToken;
import com.yupi.yudada.common.BaseResponse;
import com.yupi.yudada.common.ErrorCode;
import com.yupi.yudada.common.ResultUtils;
import com.yupi.yudada.exception.BusinessException;
import com.yupi.yudada.exception.ThrowUtils;
import com.yupi.yudada.model.dto.app.AppAddRequest;
import com.yupi.yudada.model.dto.question.QuestionAddRequest;
import com.yupi.yudada.model.dto.question.QuestionContentDTO;
import com.yupi.yudada.model.entity.App;
import com.yupi.yudada.model.entity.Question;
import com.yupi.yudada.model.entity.User;
import com.yupi.yudada.model.enums.ReviewStatusEnum;
import com.yupi.yudada.service.AiExamGenerator;
import com.yupi.yudada.service.AppService;
import com.yupi.yudada.service.QuestionService;
import com.yupi.yudada.service.UserService;
import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.loader.FileSystemDocumentLoader;
import dev.langchain4j.data.document.parser.apache.tika.ApacheTikaDocumentParser;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import javax.annotation.Resource;
import javax.servlet.http.HttpServletRequest;
import java.io.File;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@RestController
@RequestMapping("/exam")
@Slf4j
public class ExamController {

    /**
     * Redis Key 前缀
     */
    private static final String EXAM_SESSION_KEY_PREFIX = "exam:session:";

    /**
     * Redis 缓存过期时间（30分钟）
     */
    private static final long SESSION_EXPIRE_MINUTES = 30;

    @Resource
    private AiExamGenerator aiExamGenerator;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private UserService userService;

    @Resource
    private AppService appService;

    @Resource
    private QuestionService questionService;

    private final Gson gson = new Gson();

    /**
     * 创建考试 Session，返回 sessionId
     *
     * @return sessionId
     */
    @PostMapping("/session/create")
    public BaseResponse<String> createSession() {
        String sessionId = IdUtil.fastSimpleUUID();
        log.info("创建考试 Session: {}", sessionId);
        return ResultUtils.success(sessionId);
    }

    /**
     * 上传 PDF 生成试卷（带 Session 记忆）
     *
     * @param file      PDF 文件
     * @param sessionId 会话 ID（必须传递）
     * @param appName   应用名称
     * @param appDesc   应用描述
     * @return 题目列表
     */
    @PostMapping("/generate")
    public BaseResponse<List<QuestionContentDTO>> generateExam(
            @RequestParam("file") MultipartFile file,
            @RequestParam("sessionId") String sessionId,
            @RequestParam(value = "appName", required = false) String appName,
            @RequestParam(value = "appDesc", required = false) String appDesc) {
        // 1. 校验 sessionId
        if (sessionId == null || sessionId.isEmpty()) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "sessionId 不能为空");
        }

        String sessionKey = EXAM_SESSION_KEY_PREFIX + sessionId;

        // 2. 检查是否已有缓存结果
        String cachedResult = stringRedisTemplate.opsForValue().get(sessionKey);
        if (cachedResult != null) {
            log.info("从 Redis 获取缓存的考试结果, sessionId: {}", sessionId);
            List<QuestionContentDTO> cachedQuestions = parseCachedResult(cachedResult);
            if (cachedQuestions != null && !cachedQuestions.isEmpty()) {
                return ResultUtils.success(cachedQuestions);
            }
        }

        // 3. 生成新试卷（传入 appName 和 appDesc）
        List<QuestionContentDTO> questions = doGenerateExam(file, appName, appDesc);

        // 4. 缓存到 Redis
        String questionsJson = gson.toJson(questions);
        stringRedisTemplate.opsForValue().set(sessionKey, questionsJson, SESSION_EXPIRE_MINUTES, TimeUnit.MINUTES);
        log.info("考试结果已缓存到 Redis, sessionId: {}, 过期时间: {} 分钟", sessionId, SESSION_EXPIRE_MINUTES);

        return ResultUtils.success(questions);
    }

    /**
     * 获取缓存的考试结果
     *
     * @param sessionId 会话 ID
     * @return 题目列表
     */
    @GetMapping("/result")
    public BaseResponse<List<QuestionContentDTO>> getExamResult(@RequestParam("sessionId") String sessionId) {
        if (sessionId == null || sessionId.isEmpty()) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "sessionId 不能为空");
        }

        String sessionKey = EXAM_SESSION_KEY_PREFIX + sessionId;
        String cachedResult = stringRedisTemplate.opsForValue().get(sessionKey);

        if (cachedResult == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND_ERROR, "考试结果不存在或已过期");
        }

        List<QuestionContentDTO> questions = parseCachedResult(cachedResult);
        if (questions == null || questions.isEmpty()) {
            throw new BusinessException(ErrorCode.NOT_FOUND_ERROR, "考试结果为空或已过期");
        }

        return ResultUtils.success(questions);
    }

    /**
     * 删除考试 Session（清除缓存）
     *
     * @param sessionId 会话 ID
     * @return 是否删除成功
     */
    @PostMapping("/session/delete")
    public BaseResponse<Boolean> deleteSession(@RequestParam("sessionId") String sessionId) {
        if (sessionId == null || sessionId.isEmpty()) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "sessionId 不能为空");
        }

        String sessionKey = EXAM_SESSION_KEY_PREFIX + sessionId;
        Boolean deleted = stringRedisTemplate.delete(sessionKey);
        log.info("删除考试 Session: {}, 结果: {}", sessionId, deleted);
        return ResultUtils.success(deleted != null && deleted);
    }

    /**
     * 保存应用（从 Session 创建应用和题目）
     *
     * @param sessionId         会话 ID
     * @param appName          应用名称
     * @param appDesc          应用描述
     * @param appType          应用类型（0-得分类，1-测评类）
     * @param scoringStrategy  评分策略（0-自定义，1-AI）
     * @param request          HTTP 请求
     * @return 创建的应用 ID
     */
    @PostMapping("/save")
    public BaseResponse<Long> saveExam(
            @RequestParam("sessionId") String sessionId,
            @RequestParam(value = "appName", required = false) String appName,
            @RequestParam(value = "appDesc", required = false) String appDesc,
            @RequestParam(value = "appType", defaultValue = "0") Integer appType,
            @RequestParam(value = "scoringStrategy", defaultValue = "0") Integer scoringStrategy,
            HttpServletRequest request) {
        // 1. 参数校验
        if (sessionId == null || sessionId.isEmpty()) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "sessionId 不能为空");
        }
        if (appName == null || appName.isEmpty()) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "应用名称不能为空");
        }

        String sessionKey = EXAM_SESSION_KEY_PREFIX + sessionId;

        // 2. 从 Redis 获取题目列表
        String cachedResult = stringRedisTemplate.opsForValue().get(sessionKey);
        if (cachedResult == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND_ERROR, "考试结果不存在或已过期");
        }

        List<QuestionContentDTO> questions = parseCachedResult(cachedResult);
        if (questions == null || questions.isEmpty()) {
            throw new BusinessException(ErrorCode.NOT_FOUND_ERROR, "考试结果为空");
        }

        // 3. 获取当前登录用户
        User loginUser = userService.getLoginUser(request);

        // 4. 创建应用
        App app = new App();
        app.setAppName(appName);
        app.setAppDesc(appDesc);
        app.setAppType(appType);
        app.setScoringStrategy(scoringStrategy);
        app.setUserId(loginUser.getId());
        app.setReviewStatus(ReviewStatusEnum.REVIEWING.getValue());

        boolean appResult = appService.save(app);
        ThrowUtils.throwIf(!appResult, ErrorCode.OPERATION_ERROR, "创建应用失败");

        Long appId = app.getId();
        log.info("创建应用成功, appId: {}", appId);

        // 5. 创建题目
        Question question = new Question();
        question.setAppId(appId);
        question.setQuestionContent(gson.toJson(questions));
        question.setUserId(loginUser.getId());

        boolean questionResult = questionService.save(question);
        ThrowUtils.throwIf(!questionResult, ErrorCode.OPERATION_ERROR, "创建题目失败");

        log.info("创建题目成功, questionId: {}", question.getId());

        // 6. 清除 Redis 缓存
        stringRedisTemplate.delete(sessionKey);
        log.info("已清除 Redis 缓存, sessionId: {}", sessionId);

        return ResultUtils.success(appId);
    }

    /**
     * 修改指定题目
     *
     * @param sessionId   会话 ID（定位缓存）
     * @param index       题目序号（从 1 开始）
     * @param userPrompt  修改要求
     * @return 更新后的题目列表
     */
    @PostMapping("/modify")
    public BaseResponse<List<QuestionContentDTO>> modifyQuestion(
            @RequestParam("sessionId") String sessionId,
            @RequestParam("index") Integer index,
            @RequestParam("userPrompt") String userPrompt) {
        // 1. 参数校验
        if (sessionId == null || sessionId.isEmpty()) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "sessionId 不能为空");
        }
        if (index == null || index < 1) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "index 必须大于等于 1");
        }
        if (userPrompt == null || userPrompt.trim().isEmpty()) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "userPrompt 不能为空");
        }

        String sessionKey = EXAM_SESSION_KEY_PREFIX + sessionId;

        // 2. 从 Redis 获取缓存
        String cachedResult = stringRedisTemplate.opsForValue().get(sessionKey);
        if (cachedResult == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND_ERROR, "考试结果不存在或已过期");
        }

        List<QuestionContentDTO> questions = parseCachedResult(cachedResult);
        if (questions == null || questions.isEmpty()) {
            throw new BusinessException(ErrorCode.NOT_FOUND_ERROR, "考试结果为空或已过期");
        }

        // 转换为 0-based 索引
        int zeroBasedIndex = index - 1;

        // 3. 校验 index 范围
        if (zeroBasedIndex >= questions.size()) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "index 超出题目范围，当前共有 " + questions.size() + " 道题");
        }

        // 4. 构建二次 Prompt
        String currentQuestionJson = gson.toJson(questions.get(zeroBasedIndex));
        String modifyPrompt = buildModifyPrompt(currentQuestionJson, index, userPrompt);
        log.info("修改题目 Prompt: {}", modifyPrompt);

        // 5. 调用 AI 修改题目
        String aiResult = aiExamGenerator.generateQuestions(modifyPrompt);
        log.info("AI 修改返回: {}", aiResult);

        // 6. 解析 AI 返回并更新指定题目
        QuestionContentDTO updatedQuestion = parseUpdatedQuestion(aiResult);
        if (updatedQuestion == null) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "AI 修改失败，未能返回有效的题目格式");
        }

        // 替换原题目（使用 0-based 索引）
        questions.set(zeroBasedIndex, updatedQuestion);

        // 7. 写回 Redis（刷新过期时间）
        String questionsJson = gson.toJson(questions);
        stringRedisTemplate.opsForValue().set(sessionKey, questionsJson, SESSION_EXPIRE_MINUTES, TimeUnit.MINUTES);
        log.info("题目已更新并写回 Redis, sessionId: {}, index: {}", sessionId, index);

        return ResultUtils.success(questions);
    }

    /**
     * 构建修改题目的二次 Prompt
     *
     * @param currentQuestion 当前题目的 JSON
     * @param questionNumber 题目序号（从 1 开始，用于提示）
     * @param userPrompt     用户修改要求
     * @return 完整的 Prompt
     */
    private String buildModifyPrompt(String currentQuestion, int questionNumber, String userPrompt) {
        return String.format(
                "你是一个专业的题目编辑助手。下面是一道现有的题目（第 %d 题）：\n\n" +
                        "%s\n\n" +
                        "请按照以下要求修改这道题：\n" +
                        "%s\n\n" +
                        "请返回修改后的完整题目 JSON 对象，保持格式不变，只更新需要修改的部分。\n" +
                        "返回格式要求：直接返回 JSON 对象，不要包含任何其他文字或markdown标记。\n" +
                        "JSON 格式：{\"question\": \"题目描述\", \"options\": [{\"option\": \"A. 选项A内容\", \"score\": 分数}, {\"option\": \"B. 选项B内容\", \"score\": 分数}]}",
                questionNumber,
                currentQuestion,
                userPrompt
        );
    }

    /**
     * 解析 AI 返回的修改结果
     *
     * @param aiResult AI 返回的 JSON
     * @return 更新后的题目
     */
    private QuestionContentDTO parseUpdatedQuestion(String aiResult) {
        try {
            // 提取 JSON 对象
            int start = aiResult.indexOf("{");
            int end = aiResult.lastIndexOf("}");

            if (start == -1 || end == -1) {
                log.error("AI 返回格式异常，未检测到 JSON 对象");
                return null;
            }

            String cleanJson = aiResult.substring(start, end + 1);

            // 使用 AiRawQuestionDTO 解析
            AiRawQuestionDTO raw = gson.fromJson(cleanJson, AiRawQuestionDTO.class);
            if (raw == null || raw.getQuestion() == null) {
                log.error("解析修改结果失败");
                return null;
            }

            // 转换为 QuestionContentDTO
            QuestionContentDTO dto = new QuestionContentDTO();
            dto.setTitle(raw.getQuestion());
            dto.setOptions(buildOptionsFromRaw(raw));

            // 校验
            if (dto.getTitle() == null || dto.getTitle().isEmpty() ||
                    dto.getOptions() == null || dto.getOptions().isEmpty()) {
                log.error("修改后的题目无效");
                return null;
            }

            return dto;

        } catch (Exception e) {
            log.error("解析修改结果异常", e);
            return null;
        }
    }

    /**
     * 解析缓存的考试结果
     *
     * @param cachedResult 缓存的 JSON 字符串
     * @return 题目列表
     */
    private List<QuestionContentDTO> parseCachedResult(String cachedResult) {
        try {
            return gson.fromJson(cachedResult, new TypeToken<List<QuestionContentDTO>>() {}.getType());
        } catch (Exception e) {
            log.error("解析缓存考试结果失败", e);
            return null;
        }
    }

    /**
     * 核心生成试卷逻辑（内部方法）
     *
     * @param file    PDF 文件
     * @param appName 应用名称（可为空）
     * @param appDesc 应用描述（可为空）
     * @return 题目列表
     */
    private List<QuestionContentDTO> doGenerateExam(MultipartFile file, String appName, String appDesc) {
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

            // 4. 构建 Prompt（整合 PDF 内容、应用名称和应用描述）
            String prompt = buildGeneratePrompt(fullText, appName, appDesc);
            log.info("生成试卷 Prompt: {}", prompt);

            // 5. 调用 AI 生成
            String jsonResult = aiExamGenerator.generateQuestions(prompt);
            log.info("AI 原始返回: {}", jsonResult);

            // 6. 解析并转换为 QuestionContentDTO
            List<QuestionContentDTO> questions = parseAiResponse(jsonResult);

            // 7. 校验解析结果
            if (questions == null || questions.isEmpty()) {
                throw new BusinessException(ErrorCode.SYSTEM_ERROR, "AI 生成题目为空，请重试");
            }

            return questions;

        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("生成试卷失败", e);
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "生成试卷失败: " + e.getMessage());
        } finally {
            // 8. 清理临时文件
            if (tempFile != null && tempFile.exists()) {
                boolean deleted = tempFile.delete();
                if (!deleted) {
                    log.warn("临时文件删除失败: {}", tempFile.getAbsolutePath());
                }
            }
        }
    }

    /**
     * 构建生成试卷的 Prompt
     * 整合 PDF 内容、应用名称和应用描述，使生成的题目更符合应用场景
     *
     * @param pdfContent PDF 文本内容
     * @param appName    应用名称（可为空）
     * @param appDesc    应用描述（可为空）
     * @return 完整的 Prompt
     */
    private String buildGeneratePrompt(String pdfContent, String appName, String appDesc) {
        StringBuilder promptBuilder = new StringBuilder();

        // 应用上下文
        promptBuilder.append("你是一个专业的题目生成助手。请根据以下信息生成一套考试题目。\n\n");

        if (appName != null && !appName.isEmpty()) {
            promptBuilder.append("【应用名称】\n");
            promptBuilder.append(appName).append("\n\n");
        }

        if (appDesc != null && !appDesc.isEmpty()) {
            promptBuilder.append("【应用描述】\n");
            promptBuilder.append(appDesc).append("\n\n");
        }

        // PDF 内容
        promptBuilder.append("【参考内容】\n");
        promptBuilder.append(pdfContent).append("\n\n");

        // 生成要求
        promptBuilder.append("【题目要求】\n");
        promptBuilder.append("1. 根据参考内容生成 5 道选择题，题目应与参考内容紧密相关\n");
        promptBuilder.append("2. 每道题包含 2-4 个选项，正确选项 score 为 1，错误选项 score 为 0\n");
        if (appName != null || appDesc != null) {
            promptBuilder.append("3. 题目应结合应用场景，让题目更贴合实际使用场景\n");
        }
        promptBuilder.append("4. 返回格式为 JSON 数组，不要包含任何其他文字或markdown标记\n\n");

        promptBuilder.append("【返回格式】\n");
        promptBuilder.append("[\n");
        promptBuilder.append("  {\n");
        promptBuilder.append("    \"question\": \"题目描述\",\n");
        promptBuilder.append("    \"options\": [\n");
        promptBuilder.append("      {\"option\": \"A. 选项A内容\", \"score\": 1},\n");
        promptBuilder.append("      {\"option\": \"B. 选项B内容\", \"score\": 0}\n");
        promptBuilder.append("    ]\n");
        promptBuilder.append("  }\n");
        promptBuilder.append("]\n");

        return promptBuilder.toString();
    }

    /**
     * 解析 AI 返回内容，并转换为标准的 QuestionContentDTO
     *
     * @param jsonContent AI 返回的 JSON 字符串
     * @return 题目列表
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

            // 2. 校验是否为有效 JSON 数组
            try {
                JsonArray jsonArray = JsonParser.parseString(cleanJson).getAsJsonArray();
                if (jsonArray.size() == 0) {
                    throw new BusinessException(ErrorCode.SYSTEM_ERROR, "AI 返回的 JSON 数组为空");
                }
            } catch (Exception e) {
                throw new BusinessException(ErrorCode.SYSTEM_ERROR, "AI 返回格式异常，非有效的 JSON 数组");
            }

            // 3. 先用临时类接收 AI 的原始数据，支持动态选项
            Type listType = new TypeToken<List<AiRawQuestionDTO>>() {}.getType();
            List<AiRawQuestionDTO> rawList = gson.fromJson(cleanJson, listType);

            // 4. 校验解析结果
            if (rawList == null || rawList.isEmpty()) {
                throw new BusinessException(ErrorCode.SYSTEM_ERROR, "AI 生成题目解析结果为空");
            }

            // 5. 转换成 QuestionContentDTO
            List<QuestionContentDTO> resultList = new ArrayList<>();
            for (AiRawQuestionDTO raw : rawList) {
                QuestionContentDTO dto = new QuestionContentDTO();
                dto.setTitle(raw.getQuestion());

                // 动态生成选项列表，遍历 score Map 的键来确定选项数量
                List<QuestionContentDTO.Option> options = buildOptionsFromRaw(raw);
                dto.setOptions(options);

                // 校验题目有效性
                if (dto.getTitle() == null || dto.getTitle().isEmpty()) {
                    log.warn("跳过无效题目：标题为空");
                    continue;
                }
                if (dto.getOptions() == null || dto.getOptions().isEmpty()) {
                    log.warn("跳过无效题目：选项为空，标题={}", dto.getTitle());
                    continue;
                }

                resultList.add(dto);
            }

            if (resultList.isEmpty()) {
                throw new BusinessException(ErrorCode.SYSTEM_ERROR, "AI 生成题目均无效，请重试");
            }

            return resultList;

        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("JSON 解析失败, content: {}", jsonContent, e);
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "AI 生成题目解析失败");
        }
    }

    /**
     * 从 AiRawQuestionDTO 构建选项列表
     * 解析 AI 返回的 options 数组格式，提取选项 key 和 value
     *
     * AI 返回格式示例：
     * {"question": "...", "options": [{"option": "A. xxx", "score": 1}, {"option": "B. xxx", "score": 0}]}
     *
     * @param raw 原始题目数据
     * @return 选项列表
     */
    private List<QuestionContentDTO.Option> buildOptionsFromRaw(AiRawQuestionDTO raw) {
        List<AiOption> optionsList = raw.getOptions();

        if (optionsList == null || optionsList.isEmpty()) {
            return Collections.emptyList();
        }

        List<QuestionContentDTO.Option> options = new ArrayList<>();
        Pattern pattern = Pattern.compile("^([A-Z])\\.");

        for (AiOption aiOption : optionsList) {
            if (aiOption == null || aiOption.getOption() == null) {
                continue;
            }

            String optionText = aiOption.getOption();
            Matcher matcher = pattern.matcher(optionText);

            String optionKey = null;
            String optionValue = null;

            if (matcher.find()) {
                optionKey = matcher.group(1);
                optionValue = optionText.substring(matcher.end()).trim();
            } else {
                // 如果格式不符合预期，跳过或使用原文本
                log.warn("选项格式异常，无法解析 key: {}", optionText);
                continue;
            }

            QuestionContentDTO.Option option = new QuestionContentDTO.Option();
            option.setKey(optionKey);
            option.setValue(optionValue);
            option.setScore(aiOption.getScore() != null ? aiOption.getScore() : 0);

            options.add(option);
        }
        return options;
    }

    /**
     * 临时内部类：专门用于接收 AI 的原始 JSON 格式
     * AI 返回结构示例：
     * {"question": "...", "options": [{"option": "A. xxx", "score": 1}, {"option": "B. xxx", "score": 0}]}
     *
     * 注意：这个类仅在 Controller 内部使用，用于数据清洗
     */
    @Data
    @NoArgsConstructor
    static class AiRawQuestionDTO {
        /**
         * 题目标题
         */
        private String question;

        /**
         * 选项列表，每个选项包含完整文本和分数
         */
        private List<AiOption> options;
    }

    /**
     * AI 选项内部类
     */
    @Data
    @NoArgsConstructor
    static class AiOption {
        /**
         * 选项文本，格式为 "A. 选项内容" 或 "B. 选项内容"
         */
        private String option;

        /**
         * 该选项的分数
         */
        private Integer score;
    }
}
