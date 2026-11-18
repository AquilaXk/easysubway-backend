package com.easysubway.admin.audit.application.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.easysubway.admin.audit.adapter.out.persistence.InMemoryAdminAuditEventRepository;
import com.easysubway.admin.audit.domain.AdminAuditEventType;
import com.easysubway.admin.audit.domain.AdminAuditOutcome;
import com.easysubway.common.web.TrustedClientIpResolver;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

@DisplayName("관리자 감사 기록기 클라이언트 IP/UA 위생 검증")
class AdminAuditWriterTest {

	@Test
	@DisplayName("비신뢰 피어의 X-Forwarded-For는 무시하고 User-Agent 제어문자 제거 및 256자 제한을 적용한다")
	void validatesTrustedProxiesAndSanitizesUserAgent() {
		var repository = new InMemoryAdminAuditEventRepository();
		var writer = new AdminAuditWriter(repository, TrustedClientIpResolver.fromCidrs("10.0.0.0/8"));
		var auth = new UsernamePasswordAuthenticationToken(
			"admin-test",
			"N/A",
			List.of(new SimpleGrantedAuthority("admin.audit.read"))
		);

		var spoofed = new MockHttpServletRequest("POST", "/admin/test");
		spoofed.setRemoteAddr("198.51.100.88");
		spoofed.addHeader("X-Forwarded-For", "203.0.113.10");
		spoofed.addHeader("User-Agent", "Mozilla\r\nBad\t" + "X".repeat(300));
		writer.adminAction(auth, spoofed, "REPORT", "r1", "REVIEW", AdminAuditOutcome.SUCCESS, "사유");

		var recorded = repository.findRecent(AdminAuditEventType.ADMIN_ACTION, 1).getFirst();
		assertThat(recorded.clientIp()).isEqualTo("198.51.100.88");
		assertThat(recorded.userAgent())
			.doesNotContain("\r")
			.doesNotContain("\n")
			.doesNotContain("\t")
			.hasSize(256);

		var proxied = new MockHttpServletRequest("POST", "/admin/test");
		proxied.setRemoteAddr("10.0.0.9");
		proxied.addHeader("X-Forwarded-For", "198.51.100.2, 203.0.113.44");
		proxied.addHeader("User-Agent", "\u0000\u001f\u007f");
		writer.adminAction(auth, proxied, "REPORT", "r2", "REVIEW", AdminAuditOutcome.SUCCESS, "사유");

		var recordedProxied = repository.findRecent(AdminAuditEventType.ADMIN_ACTION, 1).getFirst();
		assertThat(recordedProxied.clientIp()).isEqualTo("203.0.113.44");
		assertThat(recordedProxied.userAgent()).isEqualTo("missing");
	}
}
