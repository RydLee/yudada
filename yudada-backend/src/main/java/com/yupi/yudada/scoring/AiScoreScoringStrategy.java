package com.yupi.yudada.scoring;

import cn.hutool.core.util.StrUtil;
import cn.hutool.crypto.digest.DigestUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.yupi.yudada.common.ErrorCode;
import com.yupi.yudada.exception.BusinessException;
import com.yupi.yudada.manager.AiManager;
import com.yupi.yudada.model.dto.question.QuestionAnswerDTO;
import com.yupi.yudada.model.dto.question.QuestionContentDTO;
import com.yupi.yudada.model.entity.App;
import com.yupi.yudada.model.entity.Question;
import com.yupi.yudada.model.entity.ScoringResult;
import com.yupi.yudada.model.entity.UserAnswer;
import com.yupi.yudada.model.vo.QuestionVO;
import com.yupi.yudada.service.QuestionService;
import com.yupi.yudada.service.ScoringResultService;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/*
   AI 得分类评分策略
 */
@ScoringStrategyConfig(appType = 0, scoringStrategy = 1)
public class AiScoreScoringStrategy implements ScoringStrategy {
    @Resource
    private QuestionService questionService;

    @Resource
    private ScoringResultService scoringResultService;

    @Resource
    private AiManager aiManager;

    @Resource
    private RedissonClient redissonClient;

    private static final String AI_SCORE_LOCK = "AI_SCORE_LOCK";

    /**
     * AI 评分结果缓存
     */
    private final Cache<String, String> answerCacheMap =
            Caffeine.newBuilder().initialCapacity(1024)
                    .expireAfterAccess(5L, TimeUnit.MINUTES)
                    .build();

    /**
     * AI 得分类评分系统消息
     */
    private static final String AI_SCORE_SYSTEM_MESSAGE = "你是一位严谨的判题专家，我会给你如下信息：\n" +
            "```\n" +
            "应用名称：\n" +
            "【【【应用描述】】】\n" +
            "题目和用户答案的列表：格式为 [{\"title\": \"题目\", \"options\": [{\"key\": \"A\", \"value\": \"选项内容\", \"score\": 1}], \"answer\": \"用户选择的答案\"}]\n" +
            "评分结果列表：格式为 [{\"resultName\": \"结果名称\", \"resultScoreRange\": 最低分数, \"resultDesc\": \"结果描述\"}]\n" +
            "```\n" +
            "\n" +
            "请你根据上述信息，按照以下步骤来对用户进行评分：\n" +
            "1. 根据用户答案计算总分\n" +
            "2. 根据总分从评分结果列表中找到匹配的结果\n" +
            "3. 严格按照下面的 json 格式输出结果\n" +
            "```\n" +
            "{\"resultId\": 结果ID, \"resultName\": \"结果名称\", \"resultDesc\": \"结果描述\", \"resultScore\": 总分}\n" +
            "```\n" +
            "4. 返回格式必须为 JSON 对象，不能包含'```json'、'\\n'等额外字符";

    /**
     * AI 得分类评分系统消息（无评分结果时使用）
     */
    private static final String AI_SCORE_NO_RESULT_MESSAGE = "你是一位严谨的判题专家，我会给你如下信息：\n" +
            "```\n" +
            "应用名称：\n" +
            "【【【应用描述】】】\n" +
            "题目和用户答案的列表：格式为 [{\"title\": \"题目\", \"options\": [{\"key\": \"A\", \"value\": \"选项内容\", \"score\": 1}], \"answer\": \"用户选择的答案\"}]\n" +
            "```\n" +
            "\n" +
            "请你根据上述信息，按照以下步骤来对用户进行评分：\n" +
            "1. 根据用户答案计算总分（每道题的分数是对应选项的score值）\n" +
            "2. 根据总分给出一个合理的评价\n" +
            "3. 严格按照下面的 json 格式输出结果\n" +
            "```\n" +
            "{\"resultName\": \"结果名称\", \"resultDesc\": \"详细的结果描述（至少200字）\", \"resultScore\": 总分}\n" +
            "```\n" +
            "4. 返回格式必须为 JSON 对象，不能包含'```json'、'\\n'等额外字符";

    @Override
    public UserAnswer doScore(List<String> choices, App app) throws Exception {
        Long appId = app.getId();
        String jsonStr = JSONUtil.toJsonStr(choices);
        String cacheKey = buildCacheKey(appId, jsonStr);

        // 检查缓存
        String answerJson = answerCacheMap.getIfPresent(cacheKey);
        if (StrUtil.isNotBlank(answerJson)) {
            UserAnswer userAnswer = JSONUtil.toBean(answerJson, UserAnswer.class);
            userAnswer.setAppId(appId);
            userAnswer.setAppType(app.getAppType());
            userAnswer.setScoringStrategy(app.getScoringStrategy());
            userAnswer.setChoices(jsonStr);
            return userAnswer;
        }

        // 获取分布式锁
        RLock lock = redissonClient.getLock(AI_SCORE_LOCK + cacheKey);
        try {
            boolean res = lock.tryLock(3, 15, TimeUnit.SECONDS);
            if (!res) {
                return null;
            }

            // 获取题目
            Question question = questionService.getOne(
                    Wrappers.lambdaQuery(Question.class).eq(Question::getAppId, app.getId())
            );
            if (question == null) {
                throw new BusinessException(ErrorCode.NOT_FOUND_ERROR, "该应用下没有题目，请先创建题目");
            }

            QuestionVO questionVO = QuestionVO.objToVo(question);
            List<QuestionContentDTO> questionContent = questionVO.getQuestionContent();

            // 获取评分结果
            List<ScoringResult> scoringResultList = scoringResultService.list(
                    Wrappers.lambdaQuery(ScoringResult.class)
                            .eq(ScoringResult::getAppId, app.getId())
                            .orderByDesc(ScoringResult::getResultScoreRange)
            );

            // 构建 AI 消息
            String userMessage;
            String aiResponse;

            if (scoringResultList == null || scoringResultList.isEmpty()) {
                // 无评分结果时，让 AI 直接根据总分生成评价
                userMessage = getAiScoreUserMessageWithoutResult(app, questionContent, choices);
                aiResponse = aiManager.doSyncStableRequest(AI_SCORE_NO_RESULT_MESSAGE, userMessage);
            } else {
                // 有评分结果时，按原流程匹配
                userMessage = getAiScoreUserMessage(app, questionContent, choices, scoringResultList);
                aiResponse = aiManager.doSyncStableRequest(AI_SCORE_SYSTEM_MESSAGE, userMessage);
            }
            System.out.println("AI评分响应：" + aiResponse);

            // 解析 AI 响应（通义千问直接返回内容，不需要解析）
            int start = aiResponse.indexOf("{");
            int end = aiResponse.lastIndexOf("}");
            String contentJsonStr = aiResponse.substring(start, end + 1).trim();

            JSONObject realJson = JSONUtil.parseObj(contentJsonStr);

            // 验证结果
            if (!realJson.containsKey("resultName")) {
                throw new BusinessException(ErrorCode.SYSTEM_ERROR, "AI返回格式异常，缺少resultName字段");
            }

            // 缓存结果
            answerCacheMap.put(cacheKey, JSONUtil.toJsonStr(realJson));

            // 构建返回值
            UserAnswer userAnswer = new UserAnswer();
            // resultId 可能不存在（无评分结果模式）
            if (realJson.containsKey("resultId")) {
                userAnswer.setResultId(realJson.getLong("resultId"));
            }
            userAnswer.setResultName(realJson.getStr("resultName"));
            userAnswer.setResultDesc(realJson.getStr("resultDesc"));
            userAnswer.setResultScore(realJson.getInt("resultScore"));

            userAnswer.setAppId(appId);
            userAnswer.setAppType(app.getAppType());
            userAnswer.setScoringStrategy(app.getScoringStrategy());
            userAnswer.setChoices(jsonStr);

            return userAnswer;
        } finally {
            if (lock != null && lock.isLocked() && lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    private String getAiScoreUserMessage(App app,
                                         List<QuestionContentDTO> questionContentDTOList,
                                         List<String> choices,
                                         List<ScoringResult> scoringResultList) {
        StringBuilder userMessage = new StringBuilder();
        userMessage.append("应用名称：").append(app.getAppName()).append("\n");
        userMessage.append("【【【应用描述】】】：").append(app.getAppDesc()).append("\n\n");
        userMessage.append("题目和用户答案列表：\n");

        List<QuestionAnswerDTO> questionAnswerDTOList = new ArrayList<>();
        for (int i = 0; i < questionContentDTOList.size(); i++) {
            QuestionAnswerDTO dto = new QuestionAnswerDTO();
            dto.setTitle(questionContentDTOList.get(i).getTitle());
            dto.setOptions(questionContentDTOList.get(i).getOptions());
            dto.setUserAnswer(choices.get(i));
            questionAnswerDTOList.add(dto);
        }
        userMessage.append(JSONUtil.toJsonStr(questionAnswerDTOList)).append("\n\n");

        userMessage.append("评分结果列表：\n");
        userMessage.append(JSONUtil.toJsonStr(scoringResultList));

        return userMessage.toString();
    }

    /**
     * 构建AI消息（无评分结果版本）
     */
    private String getAiScoreUserMessageWithoutResult(App app,
                                                      List<QuestionContentDTO> questionContentDTOList,
                                                      List<String> choices) {
        StringBuilder userMessage = new StringBuilder();
        userMessage.append("应用名称：").append(app.getAppName()).append("\n");
        userMessage.append("【【【应用描述】】】：").append(app.getAppDesc()).append("\n\n");
        userMessage.append("题目和用户答案列表：\n");

        List<QuestionAnswerDTO> questionAnswerDTOList = new ArrayList<>();
        for (int i = 0; i < questionContentDTOList.size(); i++) {
            QuestionAnswerDTO dto = new QuestionAnswerDTO();
            dto.setTitle(questionContentDTOList.get(i).getTitle());
            dto.setOptions(questionContentDTOList.get(i).getOptions());
            dto.setUserAnswer(choices.get(i));
            questionAnswerDTOList.add(dto);
        }
        userMessage.append(JSONUtil.toJsonStr(questionAnswerDTOList));

        return userMessage.toString();
    }

    private String buildCacheKey(long appId, String choices) {
        return DigestUtil.md5Hex(appId + ":" + choices);
    }
}
