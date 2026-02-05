package com.yupi.yudada.model.dto.question;

import lombok.Data;

import java.util.List;

/**
 * 题目答案封装类 (用于AI评分)
 */
@Data
public class QuestionAnswerDTO {

    /**
     * 题目
     */
    private String title;

    /**
     * 用户答案
     */
    private String userAnswer;

    /**
     * 题目选项列表 (用于AI评分时参考分数)
     */
    private List<QuestionContentDTO.Option> options;
}
