package com.demo.entity;

import com.baomidou.mybatisplus.annotation.TableName;

@TableName("t_order")
public class Order {

    private Long id;
    private Long amount;
    private String orderNo;
    private Integer status;

    public Long getId() { return id; }
    public Long getAmount() { return amount; }
    public String getOrderNo() { return orderNo; }
    public Integer getStatus() { return status; }
}
