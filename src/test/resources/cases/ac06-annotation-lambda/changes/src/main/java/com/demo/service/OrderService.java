package com.demo.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.demo.entity.Order;

import java.util.List;

public class OrderService {

    public List<Order> find(long id, List<Integer> states) {
        LambdaQueryWrapper<Order> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(Order::getId, id)
                .in(Order::getStatus, states)
                .and(q -> q.eq(Order::getAmount, 100L).or().eq(Order::getAmount, 200L))
                .orderByDesc(Order::getCreateTime);
        return orderMapper.selectList(wrapper);
    }
}
