package com.example.demo.tool;

import com.example.demo.model.entity.OmsOrder;
import com.example.demo.service.OrderService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.MappingIterator;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.Tool;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static net.sf.jsqlparser.parser.feature.Feature.limit;

@Component
public class QueryOrdersTool {

    @Autowired
    private OrderService orderService;
    private final String EMPTY_ORDERS="没有找到订单";
    @Autowired
    private ObjectMapper objectMapper;

    @Tool("查询用户最近的订单。参数是一个JSON字符串，包含 userId(用户ID, 必填) 和 limit(返回条数, 可选, 默认5)。" +
            "例如：{\"userId\":1,\"limit\":3}。返回订单列表。")
    public String queryRecentOrders(String arguments){
        try {
            Thread.sleep(2000);
            Map<String,Object> params = objectMapper.readValue(arguments, Map.class);
            long userId;
            Object userIdObj = params.get("userId");
            if(userIdObj instanceof Number){
                userId=((Number) userIdObj).longValue();
            }else if(userIdObj instanceof String){
                userId=Long.parseLong((String) userIdObj);
            }else{
                return "参数错误：userId应为数字";
            }
            int limit = 5;
            if(params.containsKey("limit")){
                Object limitObj=params.get("limit");
                if(limitObj instanceof Number){
                    limit =((Number)limitObj).intValue();
                }else if (limitObj instanceof String){
                    limit=Integer.parseInt((String) limitObj);
                }
            }
            List<OmsOrder> omsOrders = orderService.queryRecentOrders(userId, limit);
            if(omsOrders.isEmpty()){
                return EMPTY_ORDERS;
            }
            return omsOrders.stream()
                    .map(o -> String.format("订单ID:%d, 订单号:%s, 金额:%.2f, 状态:%d, 时间:%s",
                            o.getId(), o.getOrderSn(), o.getTotalAmount(), o.getStatus(),
                            o.getCreateTime().toString()))
                    .collect(Collectors.joining("\n"));
        } catch (Exception e) {
            return "工具执行错误: "+e.getMessage();
        }
    }
}
