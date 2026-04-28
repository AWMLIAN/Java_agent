package com.example.demo.service;

import com.example.demo.model.entity.PmsProduct;

import java.math.BigDecimal;
import java.util.List;

public interface ProductService {
    /**
     * 根据名字和价格模糊查询
     */
    public List<PmsProduct> searchProducts(String keyword, BigDecimal minPrice, BigDecimal maxPrice);
}
