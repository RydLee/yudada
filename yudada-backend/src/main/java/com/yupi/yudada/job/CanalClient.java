package com.yupi.yudada.job; // 放在 job 或 component 包下

import com.alibaba.fastjson2.JSON;
import com.alibaba.otter.canal.client.CanalConnector;
import com.alibaba.otter.canal.client.CanalConnectors;
import com.alibaba.otter.canal.protocol.CanalEntry;
import com.alibaba.otter.canal.protocol.Message;
import com.google.protobuf.ByteString;
import com.yupi.yudada.model.dto.es.QuestionEsDTO;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.data.elasticsearch.core.ElasticsearchRestTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.Map;

/**
 * Canal 客户端 (直连模式，替代 MQ)
 * 实现 CommandLineRunner，项目启动后自动运行
 */
@Component
@Slf4j
public class CanalClient implements CommandLineRunner {

    @Resource
    private ElasticsearchRestTemplate esTemplate;

    // 换成你的公网IP (本地开发) 或 内网IP (服务器部署)
    @Value("${canal.host:182.92.60.12}")
    private String canalHost;

    @Value("${canal.port:11111}")
    private int canalPort;

    @Override
    public void run(String... args) throws Exception {
        // 开启一个新线程去跑，别阻塞主线程
        new Thread(this::runCanalClient).start();
    }

    private void runCanalClient() {
        // 创建连接
        CanalConnector connector = CanalConnectors.newSingleConnector(
                new InetSocketAddress(canalHost, canalPort), "example", "", "");

        int batchSize = 1000;
        try {
            log.info("正在连接 Canal Server...");
            connector.connect();
            // 订阅所有库表，或者指定 yudada.question
            connector.subscribe("yudada\\..*");
            connector.rollback();
            log.info("Canal 连接成功，开始监听数据...");

            while (true) {
                // 拉取数据
                Message message = connector.getWithoutAck(batchSize);
                long batchId = message.getId();
                int size = message.getEntries().size();

                if (batchId == -1 || size == 0) {
                    // 没有数据，歇一会儿
                    try { Thread.sleep(1000); } catch (InterruptedException e) {}
                } else {
                    // 有数据，处理它
                    processEntry(message.getEntries());
                }
                // 提交确认
                connector.ack(batchId);
            }
        } catch (Exception e) {
            log.error("Canal 客户端异常", e);
        } finally {
            connector.disconnect();
        }
    }

    private void processEntry(List<CanalEntry.Entry> entrys) {
        for (CanalEntry.Entry entry : entrys) {
            // 只要行变更数据 (忽略事务开始/结束)
            if (entry.getEntryType() == CanalEntry.EntryType.ROWDATA) {
                CanalEntry.RowChange rowChange;
                try {
                    rowChange = CanalEntry.RowChange.parseFrom(entry.getStoreValue());
                } catch (Exception e) {
                    throw new RuntimeException("解析 Binlog 失败", e);
                }

                CanalEntry.EventType eventType = rowChange.getEventType();
                // 获取表名
                String tableName = entry.getHeader().getTableName();

                // 只处理 question 表
                if ("question".equals(tableName)) {
                    for (CanalEntry.RowData rowData : rowChange.getRowDatasList()) {
                        if (eventType == CanalEntry.EventType.DELETE) {
                            handleDelete(rowData.getBeforeColumnsList());
                        } else if (eventType == CanalEntry.EventType.INSERT || eventType == CanalEntry.EventType.UPDATE) {
                            handleUpdate(rowData.getAfterColumnsList());
                        }
                    }
                }
            }
        }
    }

    // 处理新增或更新
    private void handleUpdate(List<CanalEntry.Column> columns) {
        QuestionEsDTO esDTO = new QuestionEsDTO();
        for (CanalEntry.Column column : columns) {
            String name = column.getName();
            String value = column.getValue();

            if ("id".equals(name)) esDTO.setId(Long.valueOf(value));
            if ("appId".equals(name)) esDTO.setAppId(Long.valueOf(value));
            if ("userId".equals(name)) esDTO.setUserId(Long.valueOf(value));
            if ("isDelete".equals(name)) esDTO.setIsDelete(Integer.valueOf(value));
            // ETL清洗：提取 content
            if ("questionContent".equals(name)) {
                esDTO.setContent(extractKeywords(value)); // 复用你之前的清洗方法
            }
            // 时间处理略...可以加 try-catch 解析
        }
        esTemplate.save(esDTO);
        log.info("同步 ES 成功 (TCP模式): id={}", esDTO.getId());
    }

    // 处理删除
    private void handleDelete(List<CanalEntry.Column> columns) {
        for (CanalEntry.Column column : columns) {
            String name = column.getName();
            if ("id".equals(name)) {
                esTemplate.delete(column.getValue(), QuestionEsDTO.class);
                log.info("删除 ES 成功 (TCP模式): id={}", column.getValue());
                break;
            }
        }
    }

    private String extractKeywords(String jsonContent) {
        if (StringUtils.isBlank(jsonContent)) {
            return "";
        }
        try {
            StringBuilder sb = new StringBuilder();
            // 假设 questionContent 是一个 List 结构
            List<Map> list = JSON.parseArray(jsonContent, Map.class);
            if (list != null) {
                for (Map<String, Object> map : list) {
                    // 提取题干
                    String title = (String) map.get("title");
                    if (StringUtils.isNotBlank(title)) {
                        sb.append(title).append(" ");
                    }
                    // 提取选项内容
                    Object optionsObj = map.get("options");
                    if (optionsObj != null) {
                        String optionsJson = JSON.toJSONString(optionsObj);
                        List<Map> options = JSON.parseArray(optionsJson, Map.class);
                        for (Map<String, Object> option : options) {
                            String value = (String) option.get("value");
                            if (StringUtils.isNotBlank(value)) {
                                sb.append(value).append(" ");
                            }
                        }
                    }
                }
            }
            return sb.toString().trim();
        } catch (Exception e) {
            // 如果解析失败（比如格式不对），直接存原始 JSON 字符串兜底，防止同步中断
            log.warn("解析题目 JSON 失败，降级为存储原始字符串");
            return jsonContent;
        }
    }
}