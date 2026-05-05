package com.example.demo.tool;

import com.example.demo.model.entity.PmsProduct;
import com.example.demo.service.ProductService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.Tool;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Component
public class SearchProductTool {
    @Autowired
    private ProductService productService;

    private final String EMPTY_PRODUCT="没有找到合适商品";
    @Autowired
    private ObjectMapper objectMapper;

    @Tool("搜索商品。参数是一个JSON字符串，包含可选字段：keyword(商品名关键词), minPrice(最低价), maxPrice(最高价)。" +
            "例如：{\"keyword\":\"手机\",\"minPrice\":100.0,\"maxPrice\":5000.0}。返回符合条件的前10个商品。")
    public String searchProducts(String argument){
        try {
            Thread.sleep(2000);
            Map<String,Object> params = objectMapper.readValue(argument, Map.class);
            String keyword = (String) params.get("keyword");
            Double minPrice = params.get("minPrice") != null ? ((Number) params.get("minPrice")).doubleValue() : null;
            Double maxPrice = params.get("maxPrice") != null ? ((Number) params.get("maxPrice")).doubleValue() : null;
            BigDecimal min = minPrice != null ? BigDecimal.valueOf(minPrice) : null;
            BigDecimal max = maxPrice != null ? BigDecimal.valueOf(maxPrice) : null;
            List<PmsProduct> list = productService.searchProducts(keyword, min, max);
            if(list.isEmpty()){
                return EMPTY_PRODUCT;
            }
            return list.stream()
                    .map(p -> String.format("ID:%d, 名称:%s, 价格:%.2f, 库存:%d",
                            p.getId(), p.getName(), p.getPrice(), p.getStock()))
                    .collect(Collectors.joining("\n"));
        } catch (Exception e) {
            return "工具执行出错："+e.getMessage();
        }
    }
}
