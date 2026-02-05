package com.yupi.yudada.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.yupi.yudada.common.BaseResponse;
import com.yupi.yudada.common.ErrorCode;
import com.yupi.yudada.common.ResultUtils;
import com.yupi.yudada.model.dto.es.QuestionEsDTO;
import com.yupi.yudada.exception.BusinessException;
import com.yupi.yudada.exception.ThrowUtils;
import com.yupi.yudada.model.dto.question.QuestionQueryRequest;
import com.yupi.yudada.service.impl.EsQuestionService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.annotation.Resource;
import javax.servlet.http.HttpServletRequest;

/**
 * 题目搜索接口 (Elasticsearch)
 */
@RestController
@RequestMapping("/question_es")
@Slf4j
public class EsQuestionController {

    @Resource
    private EsQuestionService esQuestionService;

    @PostMapping("/search/page")
    public BaseResponse<Page<QuestionEsDTO>> searchQuestionEs(@RequestBody QuestionQueryRequest questionQueryRequest, HttpServletRequest request) {
        if (questionQueryRequest == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR);
        }

        // 限制爬虫/防刷 (限制单次查询数量)
        // 如果 pageSize 过大，不仅拖慢 ES，也会导致带宽压力
        ThrowUtils.throwIf(questionQueryRequest.getPageSize() > 20, ErrorCode.PARAMS_ERROR, "单次查询数量过多");

        // 调用 ES 服务进行搜索
        Page<QuestionEsDTO> questionPage = esQuestionService.searchFromEs(questionQueryRequest);

        return ResultUtils.success(questionPage);
    }
}