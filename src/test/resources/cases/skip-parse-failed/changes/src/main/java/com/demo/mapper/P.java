package com.demo.mapper;

import java.util.List;

public interface P {

    @SelectProvider(type = Builder.class, method = "build")
    List<Long> dynamic(String keyword);

    class Builder {
        public String build(String kw) {
            StringBuilder sb = new StringBuilder("SELECT id, status FROM t_order WHERE 1 = 1");
            if (kw != null) {
                sb.append(" AND name LIKE '%").append(kw).append("%'");
            }
            return sb.toString();
        }
    }
}
