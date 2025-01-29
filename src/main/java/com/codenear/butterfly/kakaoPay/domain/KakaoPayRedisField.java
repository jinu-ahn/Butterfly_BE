package com.codenear.butterfly.kakaoPay.domain;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

@NoArgsConstructor
@AllArgsConstructor
@Getter
public enum KakaoPayRedisField {
    ORDER_ID("orderId"),
    ORDER_TYPE("orderType"),
    TRANSACTION_ID("transactionId"),
    ADDRESS_ID("addressId"),
    OPTION_NAME("optionName"),
    PAYMENT_STATUS("paymentStatus"),
    PICKUP_PLACE("pickupPlace"),
    PICKUP_DATE("pickupDate"),
    PICKUP_TIME("pickupTime");

    private String fieldName;
}
