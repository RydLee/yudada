package com.yupi.yudada.service;

import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;


/**
 * AI 智能出题服务接口
 * LangChain4j 会自动通过动态代理实现此类
 */
public interface AiExamGenerator {

    @SystemMessage("你是一个专业的出题专家。请根据用户提供的文档内容，生成 10 道简短的选择题。\n" +
            "要求：\n" +
            "1. 题目必须基于文档内容。\n" +
            "2. 每道题必须包含且仅包含 2 个选项（A 和 B）。\n" +
            "3. 其中一个选项是正确的（score设为1），另一个是错误的（score设为0）。\n" +
            "4. 题目和选项的语言保持与文档一致。\n" +
            "5. 严格按照返回类型的 JSON 格式输出，不要包含任何 Markdown 标记。")
    String generateQuestions(@UserMessage String documentContent);
}