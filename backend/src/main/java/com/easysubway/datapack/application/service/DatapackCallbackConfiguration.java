package com.easysubway.datapack.application.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 콜백 수신 체인에 필요한 애플리케이션 서비스 빈을 선언한다. */
@Configuration
public class DatapackCallbackConfiguration {

    /**
     * 워크플로→backend 콜백 payload HMAC 서명/검증 빈.
     * 미설정이면 빈 키로 초기화해 모든 콜백이 HMAC 불일치로 거부된다(안전 기본값).
     */
    @Bean
    @ConditionalOnMissingBean
    CallbackSignature callbackSignature(
        @Value("${easysubway.datapack.callback-hmac-key:}") String hmacKey
    ) {
        return new CallbackSignature(hmacKey);
    }
}
