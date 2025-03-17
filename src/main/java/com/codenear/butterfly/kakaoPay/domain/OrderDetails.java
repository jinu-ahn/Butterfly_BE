package com.codenear.butterfly.kakaoPay.domain;

import com.codenear.butterfly.address.domain.Address;
import com.codenear.butterfly.kakaoPay.domain.dto.OrderStatus;
import com.codenear.butterfly.kakaoPay.domain.dto.OrderType;
import com.codenear.butterfly.kakaoPay.domain.dto.kakao.ApproveResponseDTO;
import com.codenear.butterfly.kakaoPay.domain.dto.request.BasePaymentRequestDTO;
import com.codenear.butterfly.member.domain.Member;
import com.codenear.butterfly.product.domain.Product;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

@Entity
@Getter
@NoArgsConstructor
public class OrderDetails {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "member_id", nullable = false)
    private Member member;

    @Enumerated(EnumType.STRING)
    private OrderType orderType;

    @Column(length = 16)
    private String orderCode;

    private String tid;
    private LocalDateTime createdAt;

    // 직거래 시
    private String pickupPlace;
    private LocalDate pickupDate;
    private LocalTime pickupTime;

    // 배달 시
    private String address;
    private String detailedAddress;
    private LocalDate deliverDate;

    private String productName;
    private String productImage;
    private String optionName;
    private Integer total;
    private Integer quantity;
    private Integer point;

    @Enumerated(EnumType.STRING)
    private OrderStatus orderStatus;

    @Builder
    public OrderDetails(Member member, OrderType orderType, ApproveResponseDTO approveResponseDTO, Product product, String optionName, int point) {
        this.member = member;
        this.orderType = orderType;
        this.orderCode = generateOrderCode();
        this.createdAt = LocalDateTime.parse(approveResponseDTO.getCreated_at());
        this.tid = approveResponseDTO.getTid();
        this.total = approveResponseDTO.getAmount().getTotal();
        this.productName = approveResponseDTO.getItem_name();
        this.productImage = product.getProductImage().get(0).getImageUrl();
        this.optionName = optionName;
        this.quantity = approveResponseDTO.getQuantity();
        this.orderStatus = OrderStatus.READY;
        this.point = point;

    }

    @Builder(builderMethodName = "freeOrderBuilder", buildMethodName = "buildFreeOrder")
    public OrderDetails(Member member, OrderType orderType, BasePaymentRequestDTO basePaymentRequestDTO, Product product) {
        this.member = member;
        this.orderType = orderType;
        this.orderCode = generateOrderCode();
        this.createdAt = LocalDateTime.now();
        this.total = basePaymentRequestDTO.getTotal();
        this.productName = basePaymentRequestDTO.getProductName();
        this.productImage = product.getProductImage().get(0).getImageUrl();
        this.optionName = basePaymentRequestDTO.getOptionName();
        this.quantity = basePaymentRequestDTO.getQuantity();
        this.orderStatus = OrderStatus.READY;
        this.point = basePaymentRequestDTO.getPoint();
    }

    public void addOrderTypeByPickup(String pickupPlace, LocalDate pickupDate, LocalTime pickupTime) {
        this.pickupPlace = pickupPlace;
        this.pickupDate = pickupDate;
        this.pickupTime = pickupTime;
    }

    public void addOrderTypeByDeliver(Address address, LocalDate deliverDate) {
        this.address = address.getAddress();
        this.detailedAddress = address.getDetailedAddress();
        this.deliverDate = deliverDate;
    }

    public void updateOrderStatus(OrderStatus orderStatus) {
        this.orderStatus = orderStatus;
    }

    private String generateOrderCode() {
        LocalDateTime now = LocalDateTime.now();
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyMMddHHmmssSSSS");
        return now.format(formatter);
    }
}
