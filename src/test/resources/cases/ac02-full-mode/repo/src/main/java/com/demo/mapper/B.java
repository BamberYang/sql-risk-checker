package com.demo.mapper;

import java.util.List;

public interface B {

    @Select("SELECT id FROM t_order WHERE status = #{s}")
    List<Long> byStatus(String s);
}
