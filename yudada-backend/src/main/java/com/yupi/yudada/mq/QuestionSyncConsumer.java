//package com.yupi.yudada.mq;
//
//import com.alibaba.fastjson2.JSON;
//import com.alibaba.fastjson2.JSONArray;
//import com.alibaba.fastjson2.JSONObject;
//import com.yupi.yudada.model.dto.es.QuestionEsDTO;
//import lombok.extern.slf4j.Slf4j;
//import org.apache.commons.lang3.StringUtils;
//import org.apache.rocketmq.spring.annotation.ConsumeMode;
//import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
//import org.apache.rocketmq.spring.core.RocketMQListener;
//import org.springframework.data.elasticsearch.core.ElasticsearchRestTemplate;
//import org.springframework.stereotype.Component;
//
//import javax.annotation.Resource;
//import java.util.Date;
//import java.util.List;
//import java.util.Map;
//
///**
// * 题目数据同步消费者 (Canal -> RocketMQ -> ES)
// *
// * @author 黎锐丁
// */
//@Slf4j
////@Component
////@RocketMQMessageListener(
////        topic = "example",              // Canal 默认投递的 Topic，需与 canal.properties 保持一致
////        consumerGroup = "es-sync-group-v2",
////        consumeMode = ConsumeMode.ORDERLY   // 关键：开启顺序消费，保证同一条数据的变更有序执行
////)
//public class QuestionSyncConsumer implements RocketMQListener<String> {
//
//    @Resource
//    private ElasticsearchRestTemplate esTemplate;
//
//    @Override
//    public void onMessage(String message) {
//        // 1. 基础防空
//        if (StringUtils.isBlank(message)) {
//            return;
//        }
//
//        try {
//            // 2. 解析 Canal 投递的标准 JSON 格式
//            JSONObject json = JSON.parseObject(message);
//            String type = json.getString("type"); // 操作类型: INSERT, UPDATE, DELETE
//            JSONArray dataArray = json.getJSONArray("data"); // 变更后的数据列表
//
//            if (dataArray == null || dataArray.isEmpty()) {
//                return;
//            }
//
//            // 3. 遍历每一行变更数据 (通常 Canal 一次只发一行，但格式是数组)
//            for (int i = 0; i < dataArray.size(); i++) {
//                JSONObject data = dataArray.getJSONObject(i);
//                Long id = data.getLong("id");
//
//                // --- 场景 A: 删除了数据 ---
//                if ("DELETE".equals(type)) {
//                    esTemplate.delete(String.valueOf(id), QuestionEsDTO.class);
//                    log.info("从 ES 删除题目成功, id: {}", id);
//                    continue;
//                }
//
//                // --- 场景 B: 新增或更新了数据 ---
//                if ("INSERT".equals(type) || "UPDATE".equals(type)) {
//                    // 构建 ES 文档对象
//                    QuestionEsDTO questionEsDTO = new QuestionEsDTO();
//                    questionEsDTO.setId(id);
//                    questionEsDTO.setAppId(data.getLong("appId"));
//                    questionEsDTO.setUserId(data.getLong("userId"));
//                    // 处理时间字段 (如果是时间戳直接转，如果是字符串需解析，Canal传过来通常是字符串)
//                    // questionEsDTO.setUpdateTime(data.getDate("updateTime").getTime());
//                    questionEsDTO.setIsDelete(data.getInteger("isDelete"));
//
//                    // --- 核心 ETL 逻辑: 将 MySQL 的 JSON 转为 ES 的纯文本 ---
//                    String questionContentJson = data.getString("questionContent");
//                    String cleanContent = extractKeywords(questionContentJson);
//                    questionEsDTO.setContent(cleanContent);
//
//                    // 执行保存 (save 方法包含 insert 和 update 语义)
//                    esTemplate.save(questionEsDTO);
//                    log.info("同步题目至 ES 成功, id: {}", id);
//                }
//            }
//        } catch (Exception e) {
//            log.error("消费 MQ 消息同步 ES 失败, message: {}", message, e);
//            // 生产环境建议：此处可抛出异常让 MQ 重试，或记录死信表人工补偿
//        }
//    }
//
//    /**
//     * ETL 工具方法：从复杂的题目 JSON 中提取纯文本供搜索
//     * 示例 JSON: [{"title":"Java是啥","options":[{"key":"A","value":"语言"}]}]
//     * 提取结果: "Java是啥 语言"
//     */
//    private String extractKeywords(String jsonContent) {
//        if (StringUtils.isBlank(jsonContent)) {
//            return "";
//        }
//        try {
//            StringBuilder sb = new StringBuilder();
//            // 假设 questionContent 是一个 List 结构
//            List<Map> list = JSON.parseArray(jsonContent, Map.class);
//            if (list != null) {
//                for (Map<String, Object> map : list) {
//                    // 提取题干
//                    String title = (String) map.get("title");
//                    if (StringUtils.isNotBlank(title)) {
//                        sb.append(title).append(" ");
//                    }
//                    // 提取选项内容
//                    Object optionsObj = map.get("options");
//                    if (optionsObj != null) {
//                        String optionsJson = JSON.toJSONString(optionsObj);
//                        List<Map> options = JSON.parseArray(optionsJson, Map.class);
//                        for (Map<String, Object> option : options) {
//                            String value = (String) option.get("value");
//                            if (StringUtils.isNotBlank(value)) {
//                                sb.append(value).append(" ");
//                            }
//                        }
//                    }
//                }
//            }
//            return sb.toString().trim();
//        } catch (Exception e) {
//            // 如果解析失败（比如格式不对），直接存原始 JSON 字符串兜底，防止同步中断
//            log.warn("解析题目 JSON 失败，降级为存储原始字符串");
//            return jsonContent;
//        }
//    }
//}