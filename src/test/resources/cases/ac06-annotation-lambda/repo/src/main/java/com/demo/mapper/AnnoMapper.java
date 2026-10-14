package com.demo.mapper;

import java.util.List;

public interface AnnoMapper {

    @Select("SELECT id FROM t_order WHERE status = #{st}")
    List<Long> byStatus(Integer st);

    @Select("<script>SELECT id FROM t_order WHERE id IN <foreach collection='ids' item='i' open='(' close=')' separator=','>#{i}</foreach></script>")
    List<Long> byIds(List<Long> ids);

    @SelectProvider(type = SqlBuilder.class, method = "dynamic")
    List<Long> dynamic(String kw);

    class SqlBuilder {
        public String dynamic(String kw) {
            StringBuilder sb = new StringBuilder("SELECT id FROM t_order WHERE 1 = 1");
            if (kw != null && kw.length() > 0) {
                sb.append(" AND name LIKE '%").append(kw).append("%'");
            }
            return sb.toString();
        }
    }
}
