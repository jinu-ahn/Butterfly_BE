package com.codenear.butterfly.notify.fcm.application;

import com.codenear.butterfly.consent.application.ConsentFacade;
import com.codenear.butterfly.consent.domain.Consent;
import com.codenear.butterfly.consent.domain.ConsentType;
import com.codenear.butterfly.member.application.MemberFacade;
import com.codenear.butterfly.member.domain.Member;
import com.codenear.butterfly.member.domain.dto.MemberDTO;
import com.codenear.butterfly.notify.fcm.domain.FCM;
import com.codenear.butterfly.notify.fcm.infrastructure.FCMRepository;
import com.codenear.butterfly.notify.fcm.infrastructure.FirebaseMessagingClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.InjectMocks;
import org.mockito.Captor;
import org.mockito.ArgumentCaptor;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.mock;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

@ExtendWith(MockitoExtension.class)
class FCMTokenServiceTest {
    @Mock
    private ConsentFacade consentFacade;

    @Mock
    private MemberFacade memberFacade;

    @Mock
    private FCMRepository fcmRepository;

    @Mock
    private FirebaseMessagingClient firebaseMessagingClient;

    @InjectMocks
    private FCMTokenService fcmTokenService;

    private Member testMember;
    private MemberDTO testMemberDTO;
    private String testToken;
    private List<Consent> testConsents;

    @Captor
    private ArgumentCaptor<FCM> fcmCaptor; // Mockito가 생성한 실제 객체를 캡처, save() 메서드가 호출될 때 저장된 FCM 객체를 캡처해서 필드 값을 검증할 수 있음.

    @BeforeEach
    void setUp() {
        testMember = mock(Member.class);
        testMemberDTO = mock(MemberDTO.class);
        testToken = "test-fcm-token";

        Consent marketingConsent = Consent.create(ConsentType.MARKETING, true, testMember);
        Consent eventConsent = Consent.create(ConsentType.DELIVERY_NOTIFICATION, false, testMember);

        testConsents = Arrays.asList(marketingConsent, eventConsent);
    }

    @Test
    void 새로운_FCM_토큰_생성() {
        // Given
        when(memberFacade.getMember(anyLong())).thenReturn(testMember);
        when(consentFacade.getConsents(anyLong())).thenReturn(testConsents);

        // When
        when(fcmRepository.findByToken(testToken)).thenReturn(Collections.emptyList());
        fcmTokenService.saveFCM(testToken, testMemberDTO);

        // Then
        verify(fcmRepository).save(fcmCaptor.capture());
        FCM savedFcm = fcmCaptor.getValue();

        assertEquals(testMember, savedFcm.getMember());
        assertEquals(testToken, savedFcm.getToken());
        assertNotNull(savedFcm.getLastUsedDate());

        verify(firebaseMessagingClient, times(1)).subscribeToTopic(
                eq(List.of(testToken)),
                eq("marketing")
        );
        verify(firebaseMessagingClient, never()).subscribeToTopic(
                any(),
                eq("event")
        );
    }

    @Test
    void 기존_FCM_토큰_삭제_후_새로운_FCM_생성() {
        // Given
        when(memberFacade.getMember(anyLong())).thenReturn(testMember);
        when(consentFacade.getConsents(anyLong())).thenReturn(testConsents);

        // FCM이 존재하는 경우
        FCM existingFcm = FCM.builder()
                .token(testToken)
                .member(testMember)
                .build();
        when(fcmRepository.findByToken(testToken)).thenReturn(List.of(existingFcm));

        // When
        fcmTokenService.saveFCM(testToken, testMemberDTO);

        // Then
        verify(fcmRepository, times(1)).delete(existingFcm); // 기존 FCM 삭제 확인
        verify(fcmRepository).save(fcmCaptor.capture());
        FCM savedFcm = fcmCaptor.getValue();

        assertEquals(testMember, savedFcm.getMember());
        assertEquals(testToken, savedFcm.getToken());
        assertNotNull(savedFcm.getLastUsedDate());

        verify(firebaseMessagingClient, times(1)).subscribeToTopic(
                eq(List.of(testToken)),
                eq("marketing")
        );
        verify(firebaseMessagingClient, never()).subscribeToTopic(
                any(),
                eq("event")
        );
    }
}