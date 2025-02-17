package com.codenear.butterfly.kakaoPay.domain.repository;

import com.codenear.butterfly.global.exception.ErrorCode;
import com.codenear.butterfly.kakaoPay.exception.KakaoPayException;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Repository;

import java.util.Collections;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.codenear.butterfly.kakaoPay.domain.KakaoPayRedisField.PAYMENT_STATUS;

@Repository
@RequiredArgsConstructor
public class KakaoPaymentRedisRepository {

    private static final String PAYMENT_HASH_KEY_PREFIX = "pay:";
    private static final String REMAINDER_PRODUCT_KEY_PREFIX = "remainder:product:";
    private static final int TIME_TO_LIVE_MINUTE = 15;
    private final RedisTemplate<String, String> redisTemplate;

    /**
     * Redis Hash에 여러개의 데이터를 한번에 저장
     *
     * @param memberId 멤버 아이디
     * @param fields   여러개의 필드와 값이 저장된 맵
     */
    public void addMultipleToHashSet(final Long memberId, final Map<String, String> fields) {
        // 여러 값을 한 번에 저장
        redisTemplate.opsForHash().putAll(PAYMENT_HASH_KEY_PREFIX + memberId, fields);
        ensureTTL(memberId);
    }

    /**
     * Redis Hash에서 특정 필드의 값을 가져오는 메서드
     *
     * @param memberId 멤버 아이디
     * @param field    필드 이름
     * @return 필드 값
     */
    public String getHashFieldValue(final Long memberId, final String field) {
        return (String) redisTemplate.opsForHash().get(PAYMENT_HASH_KEY_PREFIX + memberId, field);
    }

    /**
     * 멤버의 Redis Hash Table 제거
     *
     * @param memberId 멤버 아이디
     */
    public void removeHashTableKey(final Long memberId) {
        redisTemplate.delete(PAYMENT_HASH_KEY_PREFIX + memberId);
    }

    /**
     * TTL 시간 설정
     */
    private void ensureTTL(final Long memberId) {
        String key = PAYMENT_HASH_KEY_PREFIX + memberId;
        // Hash Key가 처음 생성된 경우에만 TTL 설정
        if (Boolean.FALSE.equals(redisTemplate.hasKey(key + memberId))) {
            // TTL 15분 설정 (kakao pay API가 호출 후 생성되는 tid의 유효기간은 15분 이기에 15분으로 설정)
            redisTemplate.expire(key, TIME_TO_LIVE_MINUTE, TimeUnit.MINUTES);
        }
    }

    public void savePaymentStatus(Long memberId, String status) {
        String key = PAYMENT_STATUS.getFieldName() + memberId;
        redisTemplate.opsForValue().set(key, status, 30, TimeUnit.MINUTES);
    }

    public String getPaymentStatus(Long memberId) {
        String key = PAYMENT_STATUS.getFieldName() + memberId;
        return redisTemplate.opsForValue().get(key);
    }

    public void removePaymentStatus(String key) {
        redisTemplate.delete(key);
    }

    /**
     * 상품 재고 저장
     *
     * @param productName 상품이름
     * @param quantity    재고 개수
     */
    public void saveStockQuantity(String productName, int quantity) {
        String key = REMAINDER_PRODUCT_KEY_PREFIX + productName;
        redisTemplate.opsForValue().set(key, String.valueOf(quantity));
    }

    /**
     * 상품에 대한 재고 예약 (주문 가능 개수이면 재고 차감, 주문 가능 개수보다 많으면 재고 부족 오류)
     *
     * @param productName 상품 이름
     * @param quantity    주문 개수
     */
    public void reserveStock(String productName, int quantity) {
        String key = REMAINDER_PRODUCT_KEY_PREFIX + productName;
        // 재고가 충분하면 차감하고, 부족하면 -1 반환
        String script = "local stock = tonumber(redis.call('GET', KEYS[1])) " +
                "local decrement = tonumber(ARGV[1]) " +
                "if stock and stock >= decrement then " +
                "  return redis.call('DECRBY', KEYS[1], decrement) " +
                "else " +
                "  return -1 " +
                "end";

        DefaultRedisScript<Long> redisScript = new DefaultRedisScript<>();
        redisScript.setScriptText(script);
        redisScript.setResultType(Long.class);

        Long result = redisTemplate.execute(redisScript, Collections.singletonList(key), String.valueOf(quantity));

        if (result == null || result == -1) {
            throw new KakaoPayException(ErrorCode.INSUFFICIENT_STOCK, "재고가 부족합니다");
        }
    }

    /**
     * 주문 취소 시 재고 추가
     *
     * @param productName 상품 이름
     * @param quantity    취소 재고 개수
     */
    public void restoreStockOnOrderCancellation(String productName, int quantity) {
        String key = REMAINDER_PRODUCT_KEY_PREFIX + productName;
        redisTemplate.opsForValue().increment(key, quantity);
    }
}
