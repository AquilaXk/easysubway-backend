package com.easysubway.common.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.easysubway.report.domain.FacilityReportStatus;
import java.util.Locale;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.NoSuchMessageException;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.core.env.Environment;

@SpringBootTest
@DisplayName("web message resolver")
class WebMessageResolverTest {

	@Autowired
	private WebMessageResolver messages;

	@Autowired
	private Environment environment;

	@Test
	@DisplayName("system locale fallback을 끄고 기본 한국어 bundle을 사용한다")
	void resolvesDefaultKoreanBundleWithoutSystemLocaleFallback() {
		LocaleContextHolder.setLocale(Locale.JAPAN);
		try {
			assertThat(environment.getProperty("spring.messages.fallback-to-system-locale")).isEqualTo("false");
			assertThat(messages.message("common.error.unreadable-body")).isEqualTo("요청 본문을 확인해야 합니다.");
		} finally {
			LocaleContextHolder.resetLocaleContext();
		}
	}

	@Test
	@DisplayName("message key 누락은 code 그대로 노출하지 않고 실패한다")
	void missingMessageKeyFails() {
		assertThatThrownBy(() -> messages.message("missing.backend.message.key"))
			.isInstanceOf(NoSuchMessageException.class);
	}

	@Test
	@DisplayName("관리자 enum label은 message key로 해석한다")
	void resolvesAdminEnumLabel() {
		assertThat(messages.enumLabel("admin.report.status", FacilityReportStatus.SUBMITTED)).isEqualTo("접수됨");
	}
}
