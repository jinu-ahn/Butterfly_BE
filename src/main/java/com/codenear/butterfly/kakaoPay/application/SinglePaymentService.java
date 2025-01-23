package com.codenear.butterfly.kakaoPay.application;

import com.codenear.butterfly.address.domain.Address;
import com.codenear.butterfly.address.domain.AddressRepository;
import com.codenear.butterfly.global.exception.ErrorCode;
import com.codenear.butterfly.global.util.HashMapUtil;
import com.codenear.butterfly.kakaoPay.domain.*;
import com.codenear.butterfly.kakaoPay.domain.dto.OrderType;
import com.codenear.butterfly.kakaoPay.domain.dto.PaymentStatus;
import com.codenear.butterfly.kakaoPay.domain.dto.kakao.ApproveResponseDTO;
import com.codenear.butterfly.kakaoPay.domain.dto.kakao.ReadyResponseDTO;
import com.codenear.butterfly.kakaoPay.domain.dto.request.BasePaymentRequestDTO;
import com.codenear.butterfly.kakaoPay.domain.dto.request.DeliveryPaymentRequestDTO;
import com.codenear.butterfly.kakaoPay.domain.dto.request.PickupPaymentRequestDTO;
import com.codenear.butterfly.kakaoPay.domain.repository.KakaoPaymentRedisRepository;
import com.codenear.butterfly.kakaoPay.domain.repository.OrderDetailsRepository;
import com.codenear.butterfly.kakaoPay.domain.repository.SinglePaymentRepository;
import com.codenear.butterfly.kakaoPay.exception.KakaoPayException;
import com.codenear.butterfly.member.domain.Member;
import com.codenear.butterfly.member.domain.repository.member.MemberRepository;
import com.codenear.butterfly.member.exception.MemberException;
import com.codenear.butterfly.point.domain.Point;
import com.codenear.butterfly.point.domain.PointRepository;
import com.codenear.butterfly.product.domain.Product;
import com.codenear.butterfly.product.domain.ProductInventory;
import com.codenear.butterfly.product.domain.repository.ProductInventoryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestTemplate;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import static com.codenear.butterfly.kakaoPay.domain.KakaoPayRedisField.*;

@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class SinglePaymentService {

    @Value("${kakao.payment.cid}")
    private String CID;

    @Value("${kakao.payment.secret-key-dev}")
    private String secretKey;

    @Value("${kakao.payment.host}")
    private String host;

    @Value("${kakao.payment.request-url}")
    private String requestUrl;

    private final SinglePaymentRepository singlePaymentRepository;
    private final AddressRepository addressRepository;
    private final OrderDetailsRepository orderDetailsRepository;
    private final MemberRepository memberRepository;
    private final ProductInventoryRepository productInventoryRepository;
    private final KakaoPaymentRedisRepository kakaoPaymentRedisRepository;
    private final PointRepository pointRepository;

    public ReadyResponseDTO kakaoPayReady(BasePaymentRequestDTO paymentRequestDTO, Long memberId, String orderType) {
        String partnerOrderId = UUID.randomUUID().toString();

        Map<String, Object> parameters = getKakaoPayReadyParameters(paymentRequestDTO, memberId, partnerOrderId);
        HttpEntity<Map<String, Object>> requestEntity = new HttpEntity<>(parameters, getHeaders());
        ReadyResponseDTO kakaoPayReady = new RestTemplate().postForObject(
                host + "/ready",
                requestEntity,
                ReadyResponseDTO.class);
        String tid = kakaoPayReady != null ? kakaoPayReady.getTid() : null;

        Map<String,String> fields = getKakaoPayReadyRedisFields(partnerOrderId,orderType, tid ,paymentRequestDTO);
        kakaoPaymentRedisRepository.addMultipleToHashSet(memberId,fields);
        kakaoPaymentRedisRepository.savePaymentStatus(memberId,PaymentStatus.READY.name());

        return kakaoPayReady;
    }

    @Transactional
    public void approveResponse(String pgToken, Long memberId) {
        String orderId = kakaoPaymentRedisRepository.getHashFieldValue(memberId, ORDER_ID.getFieldName());
        String transactionId = kakaoPaymentRedisRepository.getHashFieldValue(memberId, TRANSACTION_ID.getFieldName());
        String orderTypeString = kakaoPaymentRedisRepository.getHashFieldValue(memberId, ORDER_TYPE.getFieldName());
        OrderType orderType = OrderType.fromType(orderTypeString);
        String addressIdByString = kakaoPaymentRedisRepository.getHashFieldValue(memberId, ADDRESS_ID.getFieldName());
        Long addressId = addressIdByString != null ? Long.parseLong(addressIdByString) : null;
        String optionName = kakaoPaymentRedisRepository.getHashFieldValue(memberId, OPTION_NAME.getFieldName());

        Map<String, Object> parameters = getKakaoPayApproveParameters(memberId, orderId, transactionId, pgToken);
        HttpEntity<Map<String, Object>> requestEntity = new HttpEntity<>(parameters, getHeaders());
        ApproveResponseDTO approveResponseDTO = new RestTemplate().postForObject(
                host + "/approve",
                requestEntity,
                ApproveResponseDTO.class);

        ProductInventory product = productInventoryRepository.findProductByProductName(Objects.requireNonNull(approveResponseDTO).getItem_name());
        int quantity = approveResponseDTO.getQuantity();

        if (product.getStockQuantity() < quantity) {
            throw new KakaoPayException(ErrorCode.INSUFFICIENT_STOCK, "재고가 부족합니다.");
        }

        int refundedPoints = product.calculatePointRefund(quantity);

        Member member = memberRepository.findById(memberId)
                .orElseThrow(() -> new MemberException(ErrorCode.MEMBER_NOT_FOUND, ErrorCode.MEMBER_NOT_FOUND.getMessage()));

        Point point = pointRepository.findByMember(member)
                .orElseGet(() -> {
                    Point newPoint = Point.builder()
                            .point(0)
                            .build();
                    return pointRepository.save(newPoint);
                });

        point.increasePoint(refundedPoints);

        product.decreaseQuantity(quantity);
        product.increasePurchaseParticipantCount(quantity);

        SinglePayment singlePayment = SinglePayment.builder().approveResponseDTO(approveResponseDTO).build();
        Amount amount = Amount.builder().approveResponseDTO(approveResponseDTO).build();
        singlePayment.addAmount(amount);

        if (Objects.requireNonNull(approveResponseDTO).getPayment_method_type().equals(PaymentMethod.CARD.name())) {
            CardInfo cardInfo = CardInfo.builder().approveResponseDTO(approveResponseDTO).build();
            singlePayment.addCardInfo(cardInfo);
        }

        saveOrderDetails(orderType, addressId, approveResponseDTO, optionName, memberId);
        singlePaymentRepository.save(singlePayment);

        kakaoPaymentRedisRepository.removeHashTableKey(memberId);
        kakaoPaymentRedisRepository.savePaymentStatus(memberId, PaymentStatus.SUCCESS.name());
    }


    public String checkPaymentStatus(Long memberId) {
        String status = kakaoPaymentRedisRepository.getPaymentStatus(memberId);
        if (status == null) {
            return PaymentStatus.NONE.name();
        }
        return status;
    }

    public void cancelPayment(Long memberId) {
        kakaoPaymentRedisRepository.savePaymentStatus(memberId, PaymentStatus.CANCEL.name());
        kakaoPaymentRedisRepository.removeHashTableKey(memberId);
    }

    public void failPayment(Long memberId) {
        kakaoPaymentRedisRepository.savePaymentStatus(memberId, PaymentStatus.FAIL.name());
        kakaoPaymentRedisRepository.removeHashTableKey(memberId);
    }

    public void updatePaymentStatus(Long memberId) {
        String status = kakaoPaymentRedisRepository.getPaymentStatus(memberId);
        String key = PAYMENT_STATUS.getFieldName() + memberId;
        if (status == null) {
            kakaoPaymentRedisRepository.savePaymentStatus(memberId, PaymentStatus.NONE.name());
        } else if (status.equals(PaymentStatus.SUCCESS.name())) {
            kakaoPaymentRedisRepository.removePaymentStatus(key);
        }
    }

    private void saveOrderDetails(OrderType orderType, Long addressId, ApproveResponseDTO approveResponseDTO, String optionName, Long memberId) {
        Member member = memberRepository.findById(memberId)
                .orElseThrow(() -> new MemberException(ErrorCode.MEMBER_NOT_FOUND, ErrorCode.MEMBER_NOT_FOUND.getMessage()));

        Product product = productInventoryRepository.findProductByProductName(approveResponseDTO.getItem_name());

        OrderDetails orderDetails = OrderDetails.builder()
                .member(member)
                .orderType(orderType)
                .approveResponseDTO(approveResponseDTO)
                .product(product)
                .optionName(optionName)
                .build();

        switch(orderType) {
            case PICKUP -> {
                String pickupPlace = kakaoPaymentRedisRepository.getHashFieldValue(memberId, PICKUP_PLACE.getFieldName());
                LocalDate pickupDate = LocalDate.parse(kakaoPaymentRedisRepository.getHashFieldValue(memberId, PICKUP_DATE.getFieldName()));
                LocalTime pickupTime = LocalTime.parse(kakaoPaymentRedisRepository.getHashFieldValue(memberId, PICKUP_TIME.getFieldName()));
                orderDetails.addOrderTypeByPickup(pickupPlace, pickupDate, pickupTime);
            }
            case DELIVER -> {
                Address address = addressRepository.findById(addressId)
                        .orElseThrow(() -> new KakaoPayException(ErrorCode.ADDRESS_NOT_FOUND, null));
                orderDetails.addOrderTypeByDeliver(address);
            }
        }

        orderDetailsRepository.save(orderDetails);
    }

    /**
     * 카카오페이 결제 준비 단계에서 Redis에 저장할 필드를 생성
     *
     * @param partnerOrderId 파트너사 주문 ID
     * @param orderType 주문 타입
     * @param tid 카카오페이 트랜잭션 ID
     * @param paymentRequestDTO 결제 요청 정보를 담고 있는 객체 (BasePaymentRequestDTO 타입)
     *
     * @return Redis에 저장할 필드 값들을 키-값 쌍으로 담고 있는 Map 객체
     */

    private Map<String,String> getKakaoPayReadyRedisFields(
            final String partnerOrderId,
            final String orderType,
            final String tid,
            final BasePaymentRequestDTO paymentRequestDTO) {

        Map<String, String> fields = new HashMapUtil<>();
        fields.put(ORDER_ID.getFieldName(), partnerOrderId);
        fields.put(TRANSACTION_ID.getFieldName(), tid);
        fields.put(ORDER_TYPE.getFieldName(), orderType);
        fields.put(OPTION_NAME.getFieldName(), paymentRequestDTO.getOptionName());

        if(paymentRequestDTO instanceof DeliveryPaymentRequestDTO deliveryPaymentRequestDTO) {
            fields.put(ADDRESS_ID.getFieldName(), deliveryPaymentRequestDTO.getAddressId().toString());
        }

        if (paymentRequestDTO instanceof PickupPaymentRequestDTO pickupPaymentRequestDTO) {
            String pickupDate = pickupPaymentRequestDTO.getPickupDate().toString();
            String pickupTime = pickupPaymentRequestDTO.getPickupTime().toString();

            fields.put(PICKUP_PLACE.getFieldName(), pickupPaymentRequestDTO.getPickupPlace());
            fields.put(PICKUP_DATE.getFieldName(), pickupDate);
            fields.put(PICKUP_TIME.getFieldName(), pickupTime);
        }
        return fields;
    }


    private Map<String, Object> getKakaoPayReadyParameters(BasePaymentRequestDTO paymentRequestDTO, Long memberId, String partnerOrderId) {
        Map<String, Object> parameters = new HashMap<>();
        parameters.put("cid", CID);
        parameters.put("partner_order_id", partnerOrderId);
        parameters.put("partner_user_id", memberId.toString());
        parameters.put("item_name", paymentRequestDTO.getProductName());
        parameters.put("quantity", paymentRequestDTO.getQuantity());
        parameters.put("total_amount", paymentRequestDTO.getTotal());
        parameters.put("vat_amount", 0);
        parameters.put("tax_free_amount", 0);
        parameters.put("approval_url", requestUrl + "/payment/success?memberId=" + memberId);
        parameters.put("cancel_url", requestUrl + "/payment/cancel?memberId=" + memberId);
        parameters.put("fail_url", requestUrl + "/payment/fail?memberId=" + memberId);
        parameters.put("return_custom_url", "butterfly://");
        return parameters;
    }

    private Map<String, Object> getKakaoPayApproveParameters(Long memberId, String orderId, String transactionId, String pgToken) {
        Map<String, Object> parameters = new HashMap<>();
        parameters.put("cid", CID);
        parameters.put("tid", transactionId);
        parameters.put("partner_order_id", orderId);
        parameters.put("partner_user_id", memberId.toString());
        parameters.put("pg_token", pgToken);
        return parameters;
    }

    private HttpHeaders getHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("Content-Type", "application/json");
        headers.set("Authorization", "SECRET_KEY " + secretKey);
        return headers;
    }
}
