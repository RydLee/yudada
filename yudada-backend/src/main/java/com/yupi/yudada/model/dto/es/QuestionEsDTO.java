package com.yupi.yudada.model.dto.es;
import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.elasticsearch.annotations.Document;
import org.springframework.data.elasticsearch.annotations.Field;
import org.springframework.data.elasticsearch.annotations.FieldType;

import java.io.Serializable;
import java.util.Date;

// 索引名: question, 1个分片, 1个副本
@Data
@Document(indexName = "question")
public class QuestionEsDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    @Id
    private Long id;

    /**
     * 核心搜索域：包含题干 + 选项内容
     */
    @Field(type = FieldType.Text, analyzer = "ik_max_word", searchAnalyzer = "ik_smart")
    private String content;

    /**
     * 应用ID
     */
    @Field(type = FieldType.Long)
    private Long appId;

    /**
     * 题目类型
     */
    @Field(type = FieldType.Integer)
    private Integer type;

    /**
     * 创建用户ID
     */
    @Field(type = FieldType.Long)
    private Long userId;

    /**
     * 更新时间
     */
    @Field(type = FieldType.Date, format = {}, pattern = "yyyy-MM-dd HH:mm:ss")
    private Date updateTime;

    /**
     * 是否删除 (0-未删, 1-已删)
     * 必须加这个字段，否则同步消费者会报错，搜索也无法过滤删除数据
     */
    @Field(type = FieldType.Integer)
    private Integer isDelete;
}