package com.example.demo.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.example.demo.mapper.PmsProductMapper;
import com.example.demo.model.entity.PmsProduct;
import com.example.demo.service.ProductService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;

@Service
public class ProductServiceImpl implements ProductService {
    /**
     * 根据名字和价格模糊查询
     */
    @Autowired
    private PmsProductMapper pmsProductMapper;
    @Override
    public List<PmsProduct> searchProducts(String keyword, BigDecimal minPrice, BigDecimal maxPrice) {
        LambdaQueryWrapper<PmsProduct> wrapper=new LambdaQueryWrapper<>();
        //已上架
        wrapper.eq(PmsProduct::getPublishStatus,1);
        if(keyword!=null&&!keyword.isEmpty()){
            wrapper.like(PmsProduct::getName,keyword);
        }
        if(minPrice!=null){
            wrapper.ge(PmsProduct::getPrice,minPrice);
        }
        if(maxPrice!=null){
            wrapper.le(PmsProduct::getPrice,maxPrice);
        }
        Page<PmsProduct> page = new Page<>(1, 10);
        return pmsProductMapper.selectPage(page,wrapper).getRecords();
    }
}
