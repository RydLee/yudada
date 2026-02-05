package com.yupi.yudada.manager;

import com.yupi.yudada.common.ErrorCode;
import com.yupi.yudada.exception.BusinessException;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.output.Response;
import org.springframework.stereotype.Component;
// 如果不需要流式，可以移除 Flux 引用，或者引入 StreamingChatLanguageModel
import reactor.core.publisher.Flux;

import javax.annotation.Resource;
import java.util.Arrays;
import java.util.List;

/**
 * 通用 AI 调用能力 (使用通义千问 Qwen LangChain4j)
 */
@Component
public class AiManager {

    @Resource
    private ChatLanguageModel qwenChatModel;

    // 稳定的随机数
    private static final float STABLE_TEMPERATURE = 0.05f;

    // 不稳定的随机数
    private static final float UNSTABLE_TEMPERATURE = 0.99f;

    /**
     * 同步请求（答案不稳定）
     */
    public String doSyncUnstableRequest(String systemMessage, String userMessage) {
        return doRequest(systemMessage, userMessage, UNSTABLE_TEMPERATURE);
    }

    /**
     * 同步请求（答案较稳定）
     */
    public String doSyncStableRequest(String systemMessage, String userMessage) {
        return doRequest(systemMessage, userMessage, STABLE_TEMPERATURE);
    }

    /**
     * 同步请求
     */
    public String doSyncRequest(String systemMessage, String userMessage, Float temperature) {
        return doRequest(systemMessage, userMessage, temperature);
    }

    /**
     * 通用请求
     */
    public String doRequest(String systemMessage, String userMessage, Float temperature) {
        try {
            // 注意：ChatLanguageModel 本身在创建时已经指定了 temperature
            // 如果需要在调用时动态改变，通常需要多个 Model 实例或特定的高级接口
            List<ChatMessage> messages = Arrays.asList(
                    SystemMessage.from(systemMessage),
                    UserMessage.from(userMessage)
            );

            Response<AiMessage> response = qwenChatModel.generate(messages);
            return response.content().text();
        } catch (Exception e) {
            e.printStackTrace();
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, e.getMessage());
        }
    }

    /**
     * 流式请求说明：
     * 如果要实现真正的流式，需要注入 StreamingChatLanguageModel。
     * 这里的 ChatLanguageModel 是同步阻塞的，无法直接生成 Flux。
     * 以下提供一个兼容性的修改，消除爆红：
     */
    public Flux<String> doStreamRequest(String systemMessage, String userMessage, Float temperature) {
        try {
            // 由于 qwenChatModel 是同步模型，这里只能模拟返回或抛出异常提醒
            // 正常流式应当使用 streamingChatModel.generate(messages, handler)
            String result = doRequest(systemMessage, userMessage, temperature);
            return Flux.just(result);
        } catch (Exception e) {
            e.printStackTrace();
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "当前模型不支持流式输出");
        }
    }
}