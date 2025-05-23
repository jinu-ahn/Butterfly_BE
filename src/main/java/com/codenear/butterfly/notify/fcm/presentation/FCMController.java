package com.codenear.butterfly.notify.fcm.presentation;

import com.codenear.butterfly.notify.fcm.application.FCMFacade;
import com.codenear.butterfly.global.dto.ResponseDTO;
import com.codenear.butterfly.global.util.ResponseUtil;
import com.codenear.butterfly.member.domain.dto.MemberDTO;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/notify/fcm")
public class FCMController implements FCMControllerSwagger {

    private final FCMFacade fcmFacade;

    @PostMapping("/{token}")
    public ResponseEntity<ResponseDTO> registerFCM(@PathVariable String token,
                                                   @AuthenticationPrincipal MemberDTO memberDTO) {
        fcmFacade.save(token, memberDTO);
        return ResponseUtil.createSuccessResponse(null);
    }
}
