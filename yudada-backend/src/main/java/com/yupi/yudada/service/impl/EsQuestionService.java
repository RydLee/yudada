package com.yupi.yudada.service.impl;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.yupi.yudada.model.dto.es.QuestionEsDTO;
import com.yupi.yudada.model.dto.question.QuestionQueryRequest;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.elasticsearch.index.query.BoolQueryBuilder;
import org.elasticsearch.index.query.QueryBuilders;
import org.elasticsearch.search.fetch.subphase.highlight.HighlightBuilder;
import org.elasticsearch.search.sort.SortBuilders;
import org.elasticsearch.search.sort.SortOrder;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.elasticsearch.core.ElasticsearchRestTemplate;
import org.springframework.data.elasticsearch.core.SearchHit;
import org.springframework.data.elasticsearch.core.SearchHits;
import org.springframework.data.elasticsearch.core.query.NativeSearchQuery;
import org.springframework.data.elasticsearch.core.query.NativeSearchQueryBuilder;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * ES 题目搜索服务
 */
@Service
@Slf4j
public class EsQuestionService {

    @Resource
    private ElasticsearchRestTemplate elasticsearchRestTemplate;

    /**
     * 从 ES 中搜索题目
     *
     * @param questionQueryRequest 前端请求参数
     * @return 分页结果
     */
    public Page<QuestionEsDTO> searchFromEs(QuestionQueryRequest questionQueryRequest) {
        Long id = questionQueryRequest.getId();
        Long notId = questionQueryRequest.getNotId();
        String searchText = questionQueryRequest.getQuestionContent(); // 搜索关键词
        Long appId = questionQueryRequest.getAppId();
        Long userId = questionQueryRequest.getUserId();
        // 注意：ES 的分页是从 0 开始的
        int current = questionQueryRequest.getCurrent() - 1;
        int pageSize = questionQueryRequest.getPageSize();

        // 1. 构造查询条件 (BoolQuery)
        BoolQueryBuilder boolQueryBuilder = QueryBuilders.boolQuery();

        // 1.1 过滤逻辑 (Filter - 不计算相关度分数，性能更好)
        // 必须过滤掉逻辑删除的数据
        boolQueryBuilder.filter(QueryBuilders.termQuery("isDelete", 0));

        if (id != null) {
            boolQueryBuilder.filter(QueryBuilders.termQuery("id", id));
        }
        if (notId != null) {
            boolQueryBuilder.mustNot(QueryBuilders.termQuery("id", notId));
        }
        if (userId != null) {
            boolQueryBuilder.filter(QueryBuilders.termQuery("userId", userId));
        }
        if (appId != null) {
            boolQueryBuilder.filter(QueryBuilders.termQuery("appId", appId));
        }

        // 1.2 搜索逻辑 (Must - 计算相关度分数)
        // 只有当用户输入了关键词时，才进行全文检索
        if (StringUtils.isNotBlank(searchText)) {
            // content 是我们在 QuestionEsDTO 中定义的字段，包含了题干和选项的纯文本
            boolQueryBuilder.must(QueryBuilders.matchQuery("content", searchText));
        }

        // 2. 构造分页
        PageRequest pageRequest = PageRequest.of(current, pageSize);

        // 3. 构造高亮 (Highlight)
        HighlightBuilder highlightBuilder = new HighlightBuilder();
        highlightBuilder.field("content"); // 高亮 content 字段
        highlightBuilder.preTags("<span style='color:red'>"); // 高亮前缀
        highlightBuilder.postTags("</span>"); // 高亮后缀

        // 4. 构造最终查询请求 (NativeSearchQuery)
        NativeSearchQuery searchQuery = new NativeSearchQueryBuilder()
                .withQuery(boolQueryBuilder)
                .withPageable(pageRequest)
                .withHighlightBuilder(highlightBuilder)
                // 排序：先按相关度(score)降序，如果没搜词则按更新时间降序
                .withSort(SortBuilders.scoreSort().order(SortOrder.DESC))
                .withSort(SortBuilders.fieldSort("updateTime").order(SortOrder.DESC))
                .build();

        // 5. 执行查询
        SearchHits<QuestionEsDTO> searchHits = elasticsearchRestTemplate.search(searchQuery, QuestionEsDTO.class);

        // 6. 解析结果并封装返回
        List<QuestionEsDTO> resourceList = new ArrayList<>();
        // 遍历每一个命中结果
        for (SearchHit<QuestionEsDTO> hit : searchHits) {
            QuestionEsDTO questionEsDTO = hit.getContent();
            // 处理高亮：如果有高亮内容，替换原内容
            List<String> highlightFields = hit.getHighlightFields().get("content");
            if (highlightFields != null && !highlightFields.isEmpty()) {
                // 高亮片段通常是一个列表，我们取第一个即可
                questionEsDTO.setContent(highlightFields.get(0));
            }
            resourceList.add(questionEsDTO);
        }

        // 7. 构造 MyBatis Plus 风格的分页对象返回
        Page<QuestionEsDTO> page = new Page<>();
        page.setTotal(searchHits.getTotalHits());
        page.setRecords(resourceList);
        page.setCurrent(current + 1); // 返回给前端时加回 1
        page.setSize(pageSize);

        return page;
    }
}