package com.example.demo.service;

import com.example.demo.model.entity.OmsOrder;

import java.util.List;

public interface OrderService {
    /**
     * 查询某个用户最近的 N个订单
     * @param userId 用户ID(后续从SecurityContext获取)
     * @param limit 最大条数
     * @return 订单列表，按创建时间倒=倒叙返回
     */
    public List<OmsOrder> queryRecentOrders(Long userId,int limit);

    /**
     * 按id 查询单个订单
     * @param userId 用户ID(后续从SecurityContext获取)
     * return 返回最近的单个订单
     */
    public OmsOrder getOrderById(Long userId,Long orderId);
}
