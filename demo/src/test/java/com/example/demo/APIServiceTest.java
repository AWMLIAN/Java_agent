package com.example.demo;

import com.example.demo.model.entity.OmsOrder;
import com.example.demo.model.entity.PmsProduct;
import com.example.demo.service.OrderService;
import com.example.demo.service.ProductService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.util.List;

@SpringBootTest
public class APIServiceTest {
    @Autowired
    private OrderService orderService;
    @Autowired
    private ProductService productService;

    @Test
    public void testSearchProduct(){
        List<PmsProduct> list = productService.searchProducts("手机", new BigDecimal("100"), new BigDecimal("5000"));
        System.out.println("找到 " + list.size() + " 件商品");
        list.forEach(p -> System.out.println(p.getName() + " " + p.getPrice()));
    }

    @Test
    void testQueryOrders(){
        List<OmsOrder> omsOrders = orderService.queryRecentOrders(1L, 5);
        System.out.println("用户1最近订单: " + omsOrders.size());
        omsOrders.forEach(o-> System.out.println(o.getCreateTime()));
    }
}
