package com.codenear.butterfly.kakaoPay.application;

import com.codenear.butterfly.address.domain.Address;
import com.codenear.butterfly.address.domain.AddressRepository;
import com.codenear.butterfly.global.exception.ErrorCode;
import com.codenear.butterfly.global.util.HashMapUtil;
import com.codenear.butterfly.kakaoPay.domain.Amount;
import com.codenear.butterfly.kakaoPay.domain.OrderDetails;
import com.codenear.butterfly.kakaoPay.domain.SinglePayment;
import com.codenear.butterfly.kakaoPay.domain.dto.OrderType;
import com.codenear.butterfly.kakaoPay.domain.dto.PaymentStatus;
import com.codenear.butterfly.kakaoPay.domain.dto.kakao.ApproveResponseDTO;
import com.codenear.butterfly.kakaoPay.domain.dto.kakao.ReadyResponseDTO;
import com.codenear.butterfly.kakaoPay.domain.dto.kakao.handler.ApproveFreePaymentHandler;
import com.codenear.butterfly.kakaoPay.domain.dto.kakao.handler.ApproveHandler;
import com.codenear.butterfly.kakaoPay.domain.dto.kakao.handler.ApprovePaymentHandler;
import com.codenear.butterfly.kakaoPay.domain.dto.order.OrderDTO;
import com.codenear.butterfly.kakaoPay.domain.dto.rabbitmq.InventoryDecreaseMessageDTO;
import com.codenear.butterfly.kakaoPay.domain.dto.request.BasePaymentRequestDTO;
import com.codenear.butterfly.kakaoPay.domain.dto.request.DeliveryPaymentRequestDTO;
import com.codenear.butterfly.kakaoPay.domain.dto.request.PickupPaymentRequestDTO;
import com.codenear.butterfly.kakaoPay.domain.repository.KakaoPaymentRedisRepository;
import com.codenear.butterfly.kakaoPay.domain.repository.OrderDetailsRepository;
import com.codenear.butterfly.kakaoPay.domain.repository.SinglePaymentRepository;
import com.codenear.butterfly.kakaoPay.exception.KakaoPayException;
import com.codenear.butterfly.kakaoPay.util.KakaoPaymentUtil;
import com.codenear.butterfly.member.domain.Member;
import com.codenear.butterfly.member.domain.repository.member.MemberRepository;
import com.codenear.butterfly.member.exception.MemberException;
import com.codenear.butterfly.notify.NotifyMessage;
import com.codenear.butterfly.notify.fcm.application.FCMFacade;
import com.codenear.butterfly.point.domain.Point;
import com.codenear.butterfly.point.domain.PointRepository;
import com.codenear.butterfly.product.domain.Product;
import com.codenear.butterfly.product.domain.ProductInventory;
import com.codenear.butterfly.product.domain.repository.ProductInventoryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Map;
import java.util.UUID;

import static com.codenear.butterfly.kakaoPay.domain.KakaoPayRedisField.ADDRESS_ID;
import static com.codenear.butterfly.kakaoPay.domain.KakaoPayRedisField.DELIVER_DATE;
import static com.codenear.butterfly.kakaoPay.domain.KakaoPayRedisField.OPTION_NAME;
import static com.codenear.butterfly.kakaoPay.domain.KakaoPayRedisField.ORDER_ID;
import static com.codenear.butterfly.kakaoPay.domain.KakaoPayRedisField.ORDER_TYPE;
import static com.codenear.butterfly.kakaoPay.domain.KakaoPayRedisField.PAYMENT_STATUS;
import static com.codenear.butterfly.kakaoPay.domain.KakaoPayRedisField.PICKUP_DATE;
import static com.codenear.butterfly.kakaoPay.domain.KakaoPayRedisField.PICKUP_PLACE;
import static com.codenear.butterfly.kakaoPay.domain.KakaoPayRedisField.PICKUP_TIME;
import static com.codenear.butterfly.kakaoPay.domain.KakaoPayRedisField.POINT;
import static com.codenear.butterfly.kakaoPay.domain.KakaoPayRedisField.TRANSACTION_ID;

@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
@Slf4j
public class SinglePaymentService {

    private final SinglePaymentRepository singlePaymentRepository;
    private final AddressRepository addressRepository;
    private final OrderDetailsRepository orderDetailsRepository;
    private final MemberRepository memberRepository;
    private final ProductInventoryRepository productInventoryRepository;
    private final KakaoPaymentRedisRepository kakaoPaymentRedisRepository;
    private final KakaoPaymentUtil<Object> kakaoPaymentUtil;
    private final PointRepository pointRepository;
    private final ApplicationEventPublisher applicationEventPublisher;
    private final FCMFacade fcmFacade;

    @Transactional
    public ReadyResponseDTO kakaoPayReady(BasePaymentRequestDTO paymentRequestDTO, Long memberId, String orderType) {
        Member member = loadByMember(memberId);
        validateRemainingPointForPurchase(member, paymentRequestDTO.getPoint());

        String partnerOrderId = UUID.randomUUID().toString();

        // 재고 예약
        kakaoPaymentRedisRepository.reserveStock(paymentRequestDTO.getProductName(), paymentRequestDTO.getQuantity(), partnerOrderId);

        ReadyResponseDTO kakaoPayReady = null;
        if (paymentRequestDTO.getTotal() != 0) {
            Map<String, Object> parameters = kakaoPaymentUtil.getKakaoPayReadyParameters(paymentRequestDTO, memberId, partnerOrderId);
            kakaoPayReady = kakaoPaymentUtil.sendRequest("/ready", parameters, ReadyResponseDTO.class);
        }

        String tid = kakaoPayReady != null ? kakaoPayReady.getTid() : null;

        Map<String, String> fields = getKakaoPayReadyRedisFields(partnerOrderId, orderType, tid, paymentRequestDTO);
        kakaoPaymentRedisRepository.addMultipleToHashSet(memberId, fields);
        kakaoPaymentRedisRepository.savePaymentStatus(memberId, PaymentStatus.READY.name());

        if (kakaoPayReady == null) {
            approveFreeResponse(memberId, paymentRequestDTO, partnerOrderId);
            fcmFacade.sendMessage(NotifyMessage.ORDER_SUCCESS, memberId);
        }

        return kakaoPayReady;
    }

    @Transactional
    public void approveResponse(String pgToken, Long memberId) {
        String orderId = kakaoPaymentRedisRepository.getHashFieldValue(memberId, ORDER_ID.getFieldName());
        String orderTypeString = kakaoPaymentRedisRepository.getHashFieldValue(memberId, ORDER_TYPE.getFieldName());
        OrderType orderType = OrderType.fromType(orderTypeString);
        Long addressId = parsingStringToLong(memberId, ADDRESS_ID.getFieldName());
        String optionName = kakaoPaymentRedisRepository.getHashFieldValue(memberId, OPTION_NAME.getFieldName());

        Map<String, Object> parameters = kakaoPaymentUtil.getKakaoPayApproveParameters(memberId, orderId,
                kakaoPaymentRedisRepository.getHashFieldValue(memberId, TRANSACTION_ID.getFieldName()), pgToken);

        ApproveResponseDTO approveResponseDTO = kakaoPaymentUtil.sendRequest("/approve", parameters, ApproveResponseDTO.class);
        ProductInventory product = productInventoryRepository.findProductByProductName(approveResponseDTO.getItem_name());

        int usePoint = parsingStringToInt(memberId, POINT.getFieldName());
        processPaymentSuccess(memberId, orderType, addressId, optionName, product, new ApprovePaymentHandler(approveResponseDTO, usePoint));
    }

    public String checkPaymentStatus(Long memberId) {
        String status = kakaoPaymentRedisRepository.getPaymentStatus(memberId);
        if (status == null) {
            return PaymentStatus.NONE.name();
        }
        return status;
    }

    public void cancelPayment(Long memberId, String productName, int quantity) {
        restoreQuantity(productName, quantity, kakaoPaymentRedisRepository.getHashFieldValue(memberId, ORDER_ID.getFieldName()));
        kakaoPaymentRedisRepository.savePaymentStatus(memberId, PaymentStatus.CANCEL.name());
        kakaoPaymentRedisRepository.removeHashTableKey(memberId);
    }

    public void failPayment(Long memberId, String productName, int quantity) {
        restoreQuantity(productName, quantity, kakaoPaymentRedisRepository.getHashFieldValue(memberId, ORDER_ID.getFieldName()));
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

    /**
     * 주문 가능 여부 확인
     *
     * @param orderDTO 주문한 상품명과 상품개수를 담은 DTO
     */
    public void isPossibleToOrder(OrderDTO orderDTO) {
        String remainderProductQuantityStr = kakaoPaymentRedisRepository.getRemainderProductQuantity(orderDTO.productName());
        int remainderProductQuantity;

        if (remainderProductQuantityStr == null) {
            ProductInventory product = productInventoryRepository.findProductByProductName(orderDTO.productName());

            if (product == null) {
                throw new KakaoPayException(ErrorCode.INSUFFICIENT_STOCK, "재고가 부족합니다.");
            }

            remainderProductQuantity = product.getStockQuantity();
            kakaoPaymentRedisRepository.saveStockQuantity(orderDTO.productName(), remainderProductQuantity);
        } else {
            remainderProductQuantity = Integer.parseInt(remainderProductQuantityStr);
        }

        if (remainderProductQuantity < orderDTO.orderQuantity()) {
            throw new KakaoPayException(ErrorCode.INSUFFICIENT_STOCK, "재고가 부족합니다.");
        }
    }

    /**
     * 예약된 재고 반환 후 key 삭제
     *
     * @param productName 상품 이름
     * @param quantity    예약 개수
     * @param orderId     주문 id
     */
    public void restoreQuantity(String productName, int quantity, String orderId) {
        kakaoPaymentRedisRepository.restoreStockOnOrderCancellation(productName, quantity);
        kakaoPaymentRedisRepository.removeReserveProduct(productName, quantity, orderId);
    }

    /**
     * 주문 상세 정보를 생성하고 저장한다.
     *
     * @param orderType   주문 타입 (PICKUP, DELIVERY)
     * @param addressId   배송지 ID
     * @param responseDTO 결제 응답 객체 (ApproveResponseDTO 또는 BasePaymentRequestDTO)
     * @param optionName  상품 옵션명
     * @param memberId    사용자 아이디
     * @param point       사용 포인트
     * @param product     상품 정보
     */

    private <T> void saveOrderDetails(OrderType orderType,
                                      Long addressId,
                                      T responseDTO,
                                      String optionName,
                                      Long memberId,
                                      int point,
                                      Product product) {
        Member member = memberRepository.findById(memberId)
                .orElseThrow(() -> new MemberException(ErrorCode.MEMBER_NOT_FOUND, ErrorCode.MEMBER_NOT_FOUND.getMessage()));

        OrderDetails orderDetails = createOrderDetails(orderType, responseDTO, member, product, optionName, point);
        addOrderTypeDetails(orderDetails, orderType, memberId, addressId);

        orderDetailsRepository.save(orderDetails);
    }

    /**
     * 카카오페이 결제 준비 단계에서 Redis에 저장할 필드를 생성
     *
     * @param partnerOrderId    파트너사 주문 ID
     * @param orderType         주문 타입
     * @param tid               카카오페이 트랜잭션 ID
     * @param paymentRequestDTO 결제 요청 정보를 담고 있는 객체 (BasePaymentRequestDTO 타입)
     * @return Redis에 저장할 필드 값들을 키-값 쌍으로 담고 있는 Map 객체
     */

    private Map<String, String> getKakaoPayReadyRedisFields(
            final String partnerOrderId,
            final String orderType,
            final String tid,
            final BasePaymentRequestDTO paymentRequestDTO) {

        Map<String, String> fields = new HashMapUtil<>();
        fields.put(ORDER_ID.getFieldName(), partnerOrderId);
        fields.put(TRANSACTION_ID.getFieldName(), tid);
        fields.put(ORDER_TYPE.getFieldName(), orderType);
        fields.put(OPTION_NAME.getFieldName(), paymentRequestDTO.getOptionName());
        fields.put(POINT.getFieldName(), String.valueOf(paymentRequestDTO.getPoint()));

        if (paymentRequestDTO instanceof DeliveryPaymentRequestDTO deliveryPaymentRequestDTO) {
            fields.put(ADDRESS_ID.getFieldName(), deliveryPaymentRequestDTO.getAddressId().toString());
            fields.put(DELIVER_DATE.getFieldName(), deliveryPaymentRequestDTO.deliverDateFormat());
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

    /**
     * redis에 저장된 String 데이터 타입의 값을 int로 형변환 하여 반환한다.
     *
     * @param memberId 사용자 아이디
     * @param key      redis에서 가져올 키
     * @return int형 데이터
     */
    private int parsingStringToInt(Long memberId, String key) {
        String keyString = kakaoPaymentRedisRepository.getHashFieldValue(memberId, key);

        return keyString == null ? 0 : Integer.parseInt(keyString);
    }

    /**
     * redis에 저장된 String 데이터 타입의 값을 Long으로 형변환 하여 반환한다.
     *
     * @param memberId 사용자 아이디
     * @param key      redis에서 가져올 키
     * @return Long형 데이터
     */
    private Long parsingStringToLong(Long memberId, String key) {
        String keyString = kakaoPaymentRedisRepository.getHashFieldValue(memberId, key);

        return keyString == null ? null : Long.parseLong(keyString);
    }

    private void decreaseUsePoint(Long memberId, int usePoint) {
        Member member = memberRepository.findById(memberId)
                .orElseThrow(() -> new MemberException(ErrorCode.MEMBER_NOT_FOUND, ErrorCode.MEMBER_NOT_FOUND.getMessage()));

        Point point = pointRepository.findByMember(member)
                .orElseGet(() -> {
                    Point newPoint = Point.createPoint()
                            .member(member)
                            .build();
                    return pointRepository.save(newPoint);
                });

        point.decreasePoint(usePoint);
    }

    /**
     * 결제금액이 0원일 때 처리
     *
     * @param memberId          사용자 아이디
     * @param paymentRequestDTO 결제 요청 DTO
     * @param orderId           결제 UUID
     */
    private void approveFreeResponse(Long memberId, BasePaymentRequestDTO paymentRequestDTO, String orderId) {
        String orderTypeString = kakaoPaymentRedisRepository.getHashFieldValue(memberId, ORDER_TYPE.getFieldName());
        OrderType orderType = OrderType.fromType(orderTypeString);
        Long addressId = parsingStringToLong(memberId, ADDRESS_ID.getFieldName());
        String optionName = kakaoPaymentRedisRepository.getHashFieldValue(memberId, OPTION_NAME.getFieldName());

        ProductInventory product = productInventoryRepository.findProductByProductName(paymentRequestDTO.getProductName());

        processPaymentSuccess(memberId, orderType, addressId, optionName, product,
                new ApproveFreePaymentHandler(paymentRequestDTO, orderId, memberId));
    }

    /**
     * 결제 (카카오페이 or 자체결제)
     *
     * @param memberId   사용자 아이디
     * @param orderType  주문 타입 (PICKUP, DELIVERY)
     * @param addressId  배송지 아이디
     * @param optionName 선택한 상품 옵션명
     * @param product    상품 정보
     * @param handler    결제 응답 객체 (ApproveResponseDTO 또는 BasePaymentRequestDTO)
     */
    private void processPaymentSuccess(Long memberId,
                                       OrderType orderType,
                                       Long addressId,
                                       String optionName,
                                       ProductInventory product,
                                       ApproveHandler handler) {

        SinglePayment singlePayment = handler.createSinglePayment();
        Amount amount = handler.createAmount();

        singlePayment.addAmount(amount);

        handler.createCardInfo().ifPresent(singlePayment::addCardInfo);

        int usePoint = handler.getPoint();
        decreaseUsePoint(memberId, usePoint);

        saveOrderDetails(orderType, addressId, handler.getOrderDetailDto(), optionName, memberId, usePoint, product);

        singlePaymentRepository.save(singlePayment);

        kakaoPaymentRedisRepository.savePaymentStatus(memberId, PaymentStatus.SUCCESS.name());
        kakaoPaymentRedisRepository.removeReserveProduct(handler.getProductName(), handler.getQuantity(), handler.getOrderId());
        kakaoPaymentRedisRepository.removeHashTableKey(memberId);

        // DB 재고 업데이트를 위해 RabbitMQ 메시지 전송
        InventoryDecreaseMessageDTO message = new InventoryDecreaseMessageDTO(handler.getProductName(), handler.getQuantity());
        applicationEventPublisher.publishEvent(message);
    }

    /**
     * 주문 상세 정보를 생성
     * - 승인 결제(ApproveResponseDTO) 또는 무료 결제(BasePaymentRequestDTO)에 따라 다른 빌더를 사용하여 {@link OrderDetails} 객체 생성
     *
     * @param orderType   주문 유형 (PICKUP / DELIVER)
     * @param responseDTO 결제 응답 객체 (ApproveResponseDTO 또는 BasePaymentRequestDTO)
     * @param member      사용자
     * @param product     상품 정보
     * @param optionName  선택한 상품 옵션명
     * @param point       사용한 포인트 금액
     * @return 생성된 {@link OrderDetails} 객체
     * @throws IllegalArgumentException 지원하지 않는 DTO 타입일 경우 발생합니다.
     */
    private OrderDetails createOrderDetails(OrderType orderType,
                                            Object responseDTO,
                                            Member member,
                                            Product product,
                                            String optionName,
                                            int point) {

        if (responseDTO instanceof ApproveResponseDTO approveResponseDTO) {
            return createApproveOrderDetails(orderType, approveResponseDTO, member, product, optionName, point);
        }

        if (responseDTO instanceof BasePaymentRequestDTO basePaymentRequestDTO) {
            return createFreeOrderDetails(orderType, basePaymentRequestDTO, member, product);
        }

        throw new KakaoPayException(ErrorCode.INVALID_APPROVE_DATA_TYPE, "지원하지 않는 주문 정보 타입 입니다.");
    }

    /**
     * 승인 결제(ApproveResponseDTO) 주문 상세 생성
     */
    private OrderDetails createApproveOrderDetails(OrderType orderType,
                                                   ApproveResponseDTO dto,
                                                   Member member,
                                                   Product product,
                                                   String optionName,
                                                   int point) {

        return OrderDetails.builder()
                .member(member)
                .orderType(orderType)
                .approveResponseDTO(dto)
                .product(product)
                .optionName(optionName)
                .point(point)
                .build();
    }

    /**
     * 무료 결제(BasePaymentRequestDTO) 주문 상세 생성
     */
    private OrderDetails createFreeOrderDetails(OrderType orderType,
                                                BasePaymentRequestDTO dto,
                                                Member member,
                                                Product product) {

        return OrderDetails.freeOrderBuilder()
                .member(member)
                .orderType(orderType)
                .basePaymentRequestDTO(dto)
                .product(product)
                .buildFreeOrder();
    }

    /**
     * 주문 유형에 따라 {@link OrderDetails} 객체에 추가 정보 주입
     * - PICKUP : 픽업 장소, 날짜, 시간을 Redis에서 조회하여 설정
     * - DELIVER : 배송 주소와 배송 날짜를 조회하여 설정
     *
     * @param orderDetails 주문 상세 정보 객체
     * @param orderType    주문 타입 (PICKUP / DELIVER)
     * @param memberId     사용자 아이디
     * @param addressId    배송지 아이디
     * @throws KakaoPayException 주소 정보가 존재하지 않거나, 잘못된 경우 예외 발생
     */
    private void addOrderTypeDetails(OrderDetails orderDetails,
                                     OrderType orderType,
                                     Long memberId,
                                     Long addressId) {

        switch (orderType) {
            case PICKUP -> {
                String pickupPlace = kakaoPaymentRedisRepository.getHashFieldValue(memberId, PICKUP_PLACE.getFieldName());
                LocalDate pickupDate = LocalDate.parse(kakaoPaymentRedisRepository.getHashFieldValue(memberId, PICKUP_DATE.getFieldName()));
                LocalTime pickupTime = LocalTime.parse(kakaoPaymentRedisRepository.getHashFieldValue(memberId, PICKUP_TIME.getFieldName()));

                orderDetails.addOrderTypeByPickup(pickupPlace, pickupDate, pickupTime);
            }
            case DELIVER -> {
                Address address = addressRepository.findById(addressId)
                        .orElseThrow(() -> new KakaoPayException(ErrorCode.ADDRESS_NOT_FOUND, null));
                LocalDate deliverDate = LocalDate.parse(kakaoPaymentRedisRepository.getHashFieldValue(memberId, DELIVER_DATE.getFieldName()));

                orderDetails.addOrderTypeByDeliver(address, deliverDate);
            }
        }
    }

    /**
     * 멤버 가져오기
     *
     * @param memberId 사용자 아이디
     * @return 멤버 객체
     */
    private Member loadByMember(Long memberId) {
        return memberRepository.findById(memberId)
                .orElseThrow(() -> new MemberException(ErrorCode.MEMBER_NOT_FOUND, null));
    }

    private void validateRemainingPointForPurchase(Member member, int remainPoint) {
        if (remainPoint > member.getPoint().getPoint()) {
            throw new KakaoPayException(ErrorCode.INVALID_POINT_VALUE, "포인트가 부족합니다.");
        }
    }
}
