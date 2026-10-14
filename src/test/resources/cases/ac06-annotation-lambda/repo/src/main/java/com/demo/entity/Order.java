package com.demo.entity;

import com.baomidou.mybatisplus.annotation.TableName;

@TableName("t_order")
public class Order {

    private Long id;
    private Integer status;
    private Long amount;
    private java.util.Date createTime;

    public Long getId() { return id; }
    public Integer getStatus() { return status; }
    public Long getAmount() { return amount; }
    public java.util.Date getCreateTime() { return createTime; }
}
