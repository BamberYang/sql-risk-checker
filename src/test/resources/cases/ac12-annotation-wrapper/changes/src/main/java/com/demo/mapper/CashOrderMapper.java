package com.demo.mapper;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.math.BigDecimal;

public interface CashOrderMapper {

    @Select("SELECT COALESCE(SUM(repay_amount), 0) FROM cash_order ${ew.customSqlSegment}")
    BigDecimal sumByWrapper(@Param("ew") Wrapper<CashOrder> wrapper);

    @Select("SELECT COALESCE(SUM(loan_amount), 0) FROM cash_order ${ew.customSqlSegment}")
    BigDecimal sumRenamed(@Param("wrapper") Wrapper<CashOrder> wrapper);

    @Select("SELECT COALESCE(SUM(loan_amount), 0) FROM cash_order WHERE uid = #{memberId} ${ew.sqlSegment}")
    BigDecimal sumMixed(@Param("ew") Wrapper<CashOrder> wrapper, @Param("memberId") Long memberId);
}
