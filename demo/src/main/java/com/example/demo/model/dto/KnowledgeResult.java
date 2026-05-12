package com.example.demo.model.dto;

import com.example.demo.model.enums.KnowledgeType;
import lombok.AllArgsConstructor;
import lombok.Data;
import java.math.BigDecimal;

@Data
@AllArgsConstructor
public class KnowledgeResult {
    //向量数据库返回id 标识
    private String id;
    //知识类型
    private KnowledgeType type;
    //仅PRODUCT 类型有值
    private Long productId;
    private String productName;
    private BigDecimal price;
    private String brandName;
    //匹配到的原始文本内容
    private String text;
    //相似度分数
    private double score;


}
