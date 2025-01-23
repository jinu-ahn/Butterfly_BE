package com.codenear.butterfly.kakaoPay.application;

import com.codenear.butterfly.kakaoPay.domain.CancelPayment;
import com.codenear.butterfly.kakaoPay.domain.CanceledAmount;
import com.codenear.butterfly.kakaoPay.domain.OrderDetails;
import com.codenear.butterfly.kakaoPay.domain.dto.OrderStatus;
import com.codenear.butterfly.kakaoPay.domain.dto.kakao.CancelResponseDTO;
import com.codenear.butterfly.kakaoPay.domain.dto.request.CancelRequestDTO;
import com.codenear.butterfly.kakaoPay.domain.repository.CancelPaymentRepository;
import com.codenear.butterfly.kakaoPay.domain.repository.OrderDetailsRepository;
import com.codenear.butterfly.kakaoPay.util.KakaoPaymentUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.Objects;

@Service
@Transactional
@RequiredArgsConstructor
public class CancelPaymentService {
    private final CancelPaymentRepository cancelPaymentRepository;
    private final OrderDetailsRepository orderDetailsRepository;
    private final KakaoPaymentUtil<Object> kakaoPaymentUtil;

    public void cancelKakaoPay(CancelRequestDTO cancelRequestDTO) {

        OrderDetails orderDetails = orderDetailsRepository.findByOrderCode(cancelRequestDTO.getOrderCode());

        Map<String, Object> parameters = kakaoPaymentUtil.getKakaoPayCancelParameters(orderDetails,cancelRequestDTO);

        CancelResponseDTO cancelResponseDTO = kakaoPaymentUtil.sendRequest("/cancel",parameters,CancelResponseDTO.class);

        CancelPayment cancelPayment = createCancelPayment(cancelResponseDTO);

        CanceledAmount canceledAmount = createApprovedCancelAmount(cancelResponseDTO);
        cancelPayment.setCanceledAmount(canceledAmount);

        orderDetails.updateOrderStatus(OrderStatus.CANCELED);
        cancelPaymentRepository.save(cancelPayment);
    }

    private CancelPayment createCancelPayment(CancelResponseDTO cancelResponseDTO) {
        CancelPayment cancelPayment = new CancelPayment();
        cancelPayment.setAid(Objects.requireNonNull(cancelResponseDTO).getAid());
        cancelPayment.setTid(cancelResponseDTO.getTid());
        cancelPayment.setCid(cancelResponseDTO.getCid());
        cancelPayment.setStatus(cancelResponseDTO.getStatus());
        cancelPayment.setPartnerOrderId(cancelResponseDTO.getPartner_order_id());
        cancelPayment.setPartnerUserId(cancelResponseDTO.getPartner_user_id());
        cancelPayment.setPaymentMethodType(cancelResponseDTO.getPayment_method_type());
        cancelPayment.setItemName(cancelResponseDTO.getItem_name());
        cancelPayment.setItemCode(cancelResponseDTO.getItem_code());
        cancelPayment.setQuantity(cancelResponseDTO.getQuantity());
        cancelPayment.setCreatedAt(cancelResponseDTO.getCreated_at());
        cancelPayment.setApprovedAt(cancelResponseDTO.getApproved_at());
        cancelPayment.setPayload(cancelResponseDTO.getPayload());
        return cancelPayment;
    }

    private CanceledAmount createApprovedCancelAmount(CancelResponseDTO cancelResponseDTO) {
        CanceledAmount canceledAmount = new CanceledAmount();
        canceledAmount.setTotal(Objects.requireNonNull(cancelResponseDTO).getAmount().getTotal());
        canceledAmount.setTaxFree(cancelResponseDTO.getAmount().getTax_free());
        canceledAmount.setVat(cancelResponseDTO.getAmount().getVat());
        canceledAmount.setPoint(cancelResponseDTO.getAmount().getPoint());
        canceledAmount.setDiscount(cancelResponseDTO.getAmount().getDiscount());
        return canceledAmount;
    }
}
