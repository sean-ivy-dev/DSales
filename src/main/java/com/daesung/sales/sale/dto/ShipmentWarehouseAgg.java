package com.daesung.sales.sale.dto;

import com.daesung.sales.warehouse.entity.WarehouseType;
import java.time.LocalDate;

/** 발송 단위의 출고 창고(작업결과 '출고창고'). */
public interface ShipmentWarehouseAgg {
    LocalDate getTradeDate();

    Long getPartnerId();

    String getSchoolCode();

    String getTradeClass();

    /** 주문 식별자(매출등록번호). 발송 묶음 키에 들어간다 — 옛 데이터는 빈 문자열. */
    String getEntryNo();

    Long getWarehouseId();

    String getWarehouseName();

    WarehouseType getWarehouseType();
}
