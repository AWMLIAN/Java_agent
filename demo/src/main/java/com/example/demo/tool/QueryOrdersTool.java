package com.example.demo.tool;

import com.example.demo.model.entity.OmsOrder;
import com.example.demo.service.OrderService;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.Tool;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Component
public class QueryOrdersTool {

    @Autowired
    private OrderService orderService;
    @Autowired
    private ObjectMapper objectMapper;

    @Tool("查询用户最近的订单。参数是一个JSON对象，包含 userId(用户ID, 必填) 和 limit(返回条数, 可选, 默认5)。" +
            "例如：{\"userId\":1,\"limit\":3}。返回订单列表。")
    public String queryRecentOrders(String arguments){
        try {
//            Thread.sleep(2000);
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
                return "[]";
            }
            //转为Json 数组
            List<Map<String, Object>> jsonList = omsOrders.stream().map(o -> {
                Map<String, Object> map = new LinkedHashMap<>();
                map.put("id", o.getId());
                map.put("orderSn", o.getOrderSn());
                map.put("totalAmount", o.getTotalAmount());
                map.put("status", o.getStatus());
                map.put("createTime", o.getCreateTime().toString());
                return map;
            }).collect(Collectors.toList());
            return objectMapper.writeValueAsString(jsonList);
        } catch (Exception e) {
            return "工具执行错误: "+e.getMessage();
        }
    }
}
