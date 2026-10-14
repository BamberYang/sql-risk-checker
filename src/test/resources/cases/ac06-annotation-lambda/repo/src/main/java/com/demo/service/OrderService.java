package com.demo.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.demo.entity.Order;

import java.util.List;

public class OrderService {

    public List<Order> find(long id, List<Integer> states) {
        LambdaQueryWrapper<Order> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(Order::getId, id)
                .in(Order::getStatus, states)
                .orderByDesc(Order::getCreateTime);
        return orderMapper.selectList(wrapper);
    }
}
