package com.demo.mapper;

import java.util.List;

public interface AnnoMapper {

    @Select("SELECT id, status FROM t_order WHERE status = #{st}")
    List<Long> byStatus(Integer st);

    @Select("<script>SELECT id FROM t_order WHERE id IN <foreach collection='ids' item='i' open='(' close=')' separator=','>#{i}</foreach></script>")
    List<Long> byIds(List<Long> ids);

    @SelectProvider(type = SqlBuilder.class, method = "dynamic")
    List<Long> dynamic(String keyword);

    class SqlBuilder {
        public String dynamic(String kw) {
            StringBuilder sb = new StringBuilder("SELECT id, status FROM t_order WHERE 1 = 1");
            if (kw != null && !kw.isEmpty()) {
                sb.append(" AND name LIKE '%").append(kw).append("%'");
            }
            return sb.toString();
        }
    }
}
