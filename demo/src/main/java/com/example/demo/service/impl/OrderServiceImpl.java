package com.example.demo.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.demo.mapper.OmsOrderMapper;
import com.example.demo.model.entity.OmsOrder;
import com.example.demo.service.OrderService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class OrderServiceImpl implements OrderService {
    @Autowired
    private OmsOrderMapper omsOrderMapper;

    @Override
    public List<OmsOrder> queryRecentOrders(Long userId, int limit) {
        LambdaQueryWrapper<OmsOrder> wrapper=new LambdaQueryWrapper();
        wrapper.eq(OmsOrder::getMemberId,userId)
                .orderByDesc(OmsOrder::getCreateTime)
                .last("LIMIT "+Math.min(limit,20));
        return omsOrderMapper.selectList(wrapper);
    }

    @Override
    public OmsOrder getOrderById(Long userId,Long orderId) {
        LambdaQueryWrapper<OmsOrder> wrapper=new LambdaQueryWrapper<>();
        wrapper.eq(OmsOrder::getMemberId,userId)
                .eq(OmsOrder::getId,orderId);
        return omsOrderMapper.selectOne(wrapper);
    }
}
