package com.demo.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.demo.entity.Order;
import com.demo.mapper.OrderMapper;

import java.util.List;

public class BranchService {

    private OrderMapper orderMapper;

    public List<Order> findByFlag(long id, boolean flag, Long amount) {
        LambdaQueryWrapper<Order> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(Order::getId, id);
        if (flag) {
            wrapper.eq(Order::getAmount, amount);
        } else {
            wrapper.eq(Order::getOrderNo, "A");
        }
        return orderMapper.selectList(wrapper);
    }

    public int updateByType(long id, int type) {
        LambdaUpdateWrapper<Order> wrapper = new LambdaUpdateWrapper<>();
        wrapper.set(Order::getStatus, 2);
        switch (type) {
            case 1:
                wrapper.eq(Order::getId, id);
                break;
            default:
                break;
        }
        return orderMapper.update(null, wrapper);
    }
}
