package com.example.demo.tool;

import com.example.demo.model.dto.KnowledgeResult;
import com.example.demo.service.KnowledgeBaseService;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.Tool;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Component
public class SearchKnowledgeTool {
    @Autowired
    private KnowledgeBaseService knowledgeBaseService;
    @Autowired
    private ObjectMapper objectMapper;
    @Tool("搜索知识库（商品、售后政策等）。适合模糊搜索和探索性问题，用户不知道具体商品名时优先使用。"
            + "参数是一个JSON对象：{\"query\":\"用户问题或需求描述\"}。"
            + "例如：{\"query\":\"适合送礼的高端商品\"}、{\"query\":\"退货政策\"}。"
            + "返回匹配的知识条目列表。")
    public String searchKnowledge(String argument){
        try{
            if(!knowledgeBaseService.isReady()){
                return "{\"hint\":\"知识库暂不可用，请尝试使用 searchProducts 工具查询商品\"}";
            }
            Map<String,Object> params = objectMapper.readValue(argument, Map.class);
            String query = (String) params.get("query");
            if(query==null||query.isBlank()){
                return "{\"error\":\"缺少 query 参数\"}";
            }
            List<KnowledgeResult> results = knowledgeBaseService.search(query, 5);
            if(results.isEmpty()){
                return "{\"hint\":\"未找到相关知识，请尝试使用 searchProducts 工具查询商品\"}";
            }
            List<Map<String, Object>> jsonList = results.stream().map(r -> {
                Map<String, Object> map = new LinkedHashMap<>();
                map.put("id", r.getId());
                map.put("type", r.getType());
                map.put("productName", r.getProductName());
                map.put("price", r.getPrice());
                map.put("brandName", r.getBrandName());
                map.put("text", r.getText());
                map.put("score", Math.round(r.getScore() * 100.0) / 100.0);
                return map;
            }).collect(Collectors.toList());
            return objectMapper.writeValueAsString(jsonList);
        }catch (Exception e){
            return "{\"error\":\"工具执行出错：" + e.getMessage() + "\"}";
        }
    }
}
