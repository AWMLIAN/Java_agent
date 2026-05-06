package com.example.demo.component;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

/**
 * 工具结果处理器：寄生压缩核心
 * 对长工具结果做结构化摘要，当前LLM推理与未来记忆压缩复用同一份产出
 * 职责边界：仅服务当前LLM推理，不负责长期记忆压缩策略
 */
@Slf4j
@Component
public class ToolResultProcessor {
    private static final int MAX_RESULT_LENGTH=2000;
    private static final DateTimeFormatter DT_FMT=DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private final ObjectMapper mapper = new ObjectMapper();
    /**
     * 根据工具分配处理策略
     */
    public String process(String toolName,String rawResult){
        if(rawResult==null||rawResult.isEmpty()){
            return "查询结果为空";
        }
        //短结果不做处理
        if(rawResult.length()<MAX_RESULT_LENGTH){
            log.info("Tool:{} 返回结果字数少于{},没有进行结果压缩",toolName,MAX_RESULT_LENGTH);
            return rawResult;
        }
        return switch (toolName){
            case "queryRecentOrders" ->
                extractOrderSummary(toolName,rawResult);
            case "searchProducts" ->
                extractProductSummary(toolName,rawResult);
            default ->
                    extractGenericSummary(toolName, rawResult);//通用兜底
        };
    }

    private String extractGenericSummary(String toolName, String rawResult) {
        try {
            List<Map<String,Object>> items=mapper.readValue(rawResult, new TypeReference<>() {});
            StringBuilder sb=new StringBuilder();
            sb.append(String.format("【%s查询结果】 查询时间：%s, 共%s条查询结果:\n "));
            for (Map<String, Object> item : items) {
                sb.append(String.format("id：%s,name:%s",
                        item.getOrDefault("id","N/A"),
                        item.getOrDefault("name","N/A")));
                if(item.containsKey("description")){
                    sb.append("description").append(item.get("description"));
                }
                sb.append("\n");
            }
            log.info("Tool:{} 结果内容过长，通过通用兜底摘要生成压缩结果", toolName);
            return sb.toString();
        } catch (Exception e) {
            log.warn("Generic summary extraction failed for tool: {}, fallback to truncate", toolName, e);
            return safeTruncate(rawResult);
        }
    }

    private String safeTruncate(String rawResult) {
        return rawResult.substring(0,MAX_RESULT_LENGTH)
                +String.format("\n...[结果已截断，共 %d 字符，完整数据已存档]]",rawResult.length());
    }

    //商品结构总结
    private String extractProductSummary(String toolName,String rawResult) {
        //名称，价格，库存
        try {
            List<Map<String,Object>> products = mapper.readValue(rawResult, new TypeReference<>() {});
            StringBuilder sb=new StringBuilder();
            sb.append(String.format("【searchProducts搜索结果】 搜索时间：%s，搜索商品总数：%s\n",
                    LocalDateTime.now().format(DT_FMT),products.size()));
            for (Map<String, Object> product : products) {
                sb.append(String.format("商品名称：%s,商品价格：%s,库存：%s",
                        product.getOrDefault("name","未知"),
                        product.getOrDefault("price","未知"),
                        product.getOrDefault("stock","未知")));
                sb.append("\n");
            }
            log.info("Tool：{} 结果内容过长，对结果进行了压缩",toolName);
            return sb.toString();
        } catch (Exception e) {
            log.warn("Failed to extract product summary,fallback to truncate");
            return safeTruncate(rawResult);
        }
    }

    //订单结果总结
    private String extractOrderSummary(String toolName,String rawResult) {
        try {
            List<Map<String,Object>> orders=mapper.readValue(rawResult,new TypeReference<>(){});
            StringBuilder sb=new StringBuilder();
            sb.append(String.format("【queryRecentOrders查询结果】 查询时间：%s，查询订单总数：%s\n",
                    LocalDateTime.now().format(DT_FMT),orders.size()));
            for (Map<String, Object> order : orders) {
                //订单号，金额，状态，时间
                sb.append(String.format("订单号：%s,金额：%s,订单状态：%s,下单时间：%s",
                        order.getOrDefault("orderSn","未知"),
                        order.getOrDefault("totalAmount","未知"),
                        order.getOrDefault("status","未知"),
                        order.getOrDefault("createTime","未知")));
                sb.append("\n");
            }
            log.info("Tool：{} 结果内容过长，对结果进行了压缩",toolName);
            return sb.toString();
        } catch (Exception e) {
            log.warn("Failed to extract order summary,fallback to truncate");
            return safeTruncate(rawResult);
        }
    }
}



















