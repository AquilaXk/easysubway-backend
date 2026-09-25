package com.easysubway.report.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

@SpringBootTest(properties = {
	"easysubway.admin.username=admin-test",
	"easysubway.admin.password=admin-test-password",
	"easysubway.report.abuse-control.window-seconds=60",
	"easysubway.report.abuse-control.upload-intent-limit=2",
	"easysubway.report.abuse-control.upload-claim-limit=2",
	"easysubway.report.abuse-control.report-submit-limit=2",
	"easysubway.report.abuse-control.status-limit=2",
	"easysubway.report.abuse-control.confirm-limit=2",
	"easysubway.report.abuse-control.max-counter-keys=64",
	"easysubway.auth.client-ip.trusted-proxies=10.0.0.0/8"
})
@AutoConfigureMockMvc
@DisplayName("시설 신고 abuse control")
class FacilityReportAbuseControlTest {

	@Autowired
	private MockMvc mockMvc;

	@TestConfiguration
	static class FixedClockConfiguration {

		@Bean
		Clock facilityReportAbuseControlClock() {
			return Clock.fixed(Instant.parse("2026-06-22T00:00:00Z"), ZoneOffset.UTC);
		}

	}

	@Test
	@DisplayName("업로드 intent 생성은 client별 fixed window 한도를 넘으면 429를 반환한다")
	void uploadIntentCreationIsRateLimitedByClient() throws Exception {
		createUploadIntent("abuse-upload-intent-1", "198.51.100.10").andExpect(status().isCreated());
		createUploadIntent("abuse-upload-intent-2", "198.51.100.10").andExpect(status().isCreated());

		createUploadIntent("abuse-upload-intent-3", "198.51.100.10")
			.andExpect(status().isTooManyRequests());
	}

	@Test
	@DisplayName("업로드 claim 경로는 client별 fixed window 한도를 넘으면 429를 반환한다")
	void uploadClaimIsRateLimitedByClient() throws Exception {
		claimUpload("abuse-upload-claim-1", "198.51.100.11").andExpect(status().isBadRequest());
		claimUpload("abuse-upload-claim-2", "198.51.100.11").andExpect(status().isBadRequest());

		claimUpload("abuse-upload-claim-3", "198.51.100.11")
			.andExpect(status().isTooManyRequests());
	}

	@Test
	@DisplayName("receipt 신고 생성은 client별 fixed window 한도를 넘으면 429를 반환한다")
	void receiptReportSubmissionIsRateLimitedByClient() throws Exception {
		createReceiptReport("abuse-report-submit-1", "198.51.100.12").andExpect(status().isCreated());
		createReceiptReport("abuse-report-submit-2", "198.51.100.12").andExpect(status().isCreated());

		createReceiptReport("abuse-report-submit-3", "198.51.100.12")
			.andExpect(status().isTooManyRequests());
	}

	@Test
	@DisplayName("receipt 상태 조회는 client별 fixed window 한도를 넘으면 429를 반환한다")
	void receiptStatusLookupIsRateLimitedByClient() throws Exception {
		String response = createReceiptReport("abuse-report-status-source", "198.51.100.13")
			.andExpect(status().isCreated())
			.andReturn()
			.getResponse()
			.getContentAsString();
		String reportId = JsonPath.read(response, "$.data.id");
		String receiptToken = JsonPath.read(response, "$.data.receiptToken");

		getReceiptStatus(reportId, receiptToken, "198.51.100.14").andExpect(status().isOk());
		getReceiptStatus(reportId, receiptToken, "198.51.100.14").andExpect(status().isOk());

		getReceiptStatus(reportId, receiptToken, "198.51.100.14")
			.andExpect(status().isTooManyRequests());
	}

	@Test
	@DisplayName("receipt 확인 경로는 client별 fixed window 한도를 넘으면 429를 반환한다")
	void receiptConfirmIsRateLimitedByClient() throws Exception {
		confirmReceipt("unknown-report-confirm-1", "198.51.100.15").andExpect(status().isNotFound());
		confirmReceipt("unknown-report-confirm-2", "198.51.100.15").andExpect(status().isNotFound());

		confirmReceipt("unknown-report-confirm-3", "198.51.100.15")
			.andExpect(status().isTooManyRequests());
	}

	@Test
	@DisplayName("trusted proxy 요청은 X-Forwarded-For의 원 client IP로 한도를 분리한다")
	void trustedProxyForwardedClientSeparatesRateLimitIdentity() throws Exception {
		getUnknownStatusFromForwardedClient("203.0.113.10").andExpect(status().isNotFound());
		getUnknownStatusFromForwardedClient("203.0.113.10").andExpect(status().isNotFound());

		getUnknownStatusFromForwardedClient("203.0.113.10")
			.andExpect(status().isTooManyRequests());

		getUnknownStatusFromForwardedClient("203.0.113.11")
			.andExpect(status().isNotFound());
	}

	@Test
	@DisplayName("trusted proxy 요청은 spoofed X-Forwarded-For leftmost 값을 client identity로 쓰지 않는다")
	void trustedProxyIgnoresSpoofedLeftmostForwardedFor() throws Exception {
		getUnknownStatusFromForwardedChain("198.51.100.200, 203.0.113.20").andExpect(status().isNotFound());
		getUnknownStatusFromForwardedChain("198.51.100.201, 203.0.113.20").andExpect(status().isNotFound());

		getUnknownStatusFromForwardedChain("198.51.100.202, 203.0.113.20")
			.andExpect(status().isTooManyRequests());
	}

	@Test
	@DisplayName("trusted proxy가 아닌 요청은 X-Forwarded-For로 client identity를 바꾸지 못한다")
	void untrustedRemoteCannotSpoofForwardedForIdentity() throws Exception {
		getUnknownStatusFromUntrustedForwardedClient("198.51.100.210", "203.0.113.30")
			.andExpect(status().isNotFound());
		getUnknownStatusFromUntrustedForwardedClient("198.51.100.210", "203.0.113.31")
			.andExpect(status().isNotFound());

		getUnknownStatusFromUntrustedForwardedClient("198.51.100.210", "203.0.113.32")
			.andExpect(status().isTooManyRequests());
	}

	@Test
	@DisplayName("trusted proxy 요청은 invalid X-Forwarded-For 값을 unknown client identity로 묶는다")
	void trustedProxyInvalidForwardedForUsesUnknownIdentity() throws Exception {
		getUnknownStatusFromForwardedChain("not-an-ip-1").andExpect(status().isNotFound());
		getUnknownStatusFromForwardedChain("not-an-ip-2").andExpect(status().isNotFound());

		getUnknownStatusFromForwardedChain("not-an-ip-3")
			.andExpect(status().isTooManyRequests());
	}

	@Test
	@DisplayName("limiter는 현재 window 신규 client key 증가를 상한에서 차단한다")
	void limiterRejectsNewClientIdentityWhenCurrentWindowKeyCapIsReached() {
		var limiter = new FacilityReportAbuseControlLimiter(
			new FacilityReportAbuseControlPolicy(60, 2, "local", completeLimits(100)),
			Clock.fixed(Instant.parse("2026-06-22T00:00:00Z"), ZoneOffset.UTC)
		);

		assertThat(limiter.tryAcquire(ReportAbuseGroup.STATUS, "ip:203.0.113.1")).isTrue();
		assertThat(limiter.tryAcquire(ReportAbuseGroup.STATUS, "ip:203.0.113.2")).isTrue();
		assertThat(limiter.tryAcquire(ReportAbuseGroup.STATUS, "ip:203.0.113.1")).isTrue();
		assertThat(limiter.tryAcquire(ReportAbuseGroup.STATUS, "ip:203.0.113.3")).isFalse();
	}

	@Test
	@DisplayName("abuse control policy는 누락되거나 비활성화된 endpoint limit을 거부한다")
	void policyRejectsMissingOrNonPositiveLimits() {
		assertThatThrownBy(() -> new FacilityReportAbuseControlPolicy(
			60,
			10,
			"local",
			Map.of(ReportAbuseGroup.STATUS, 1)
		)).isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("UPLOAD_INTENT");

		assertThatThrownBy(() -> new FacilityReportAbuseControlPolicy(
			60,
			10,
			"local",
			Map.of(
				ReportAbuseGroup.UPLOAD_INTENT, 1,
				ReportAbuseGroup.UPLOAD_CLAIM, 1,
				ReportAbuseGroup.REPORT_SUBMIT, 1,
				ReportAbuseGroup.STATUS, 0,
				ReportAbuseGroup.CONFIRM, 1
			)
		)).isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("STATUS");
	}

	@Test
	@DisplayName("local abuse store mode는 운영 release blocker로 식별된다")
	void localStoreModeIsReleaseBlockingUntilDistributedStoreIsSelected() {
		var policy = new FacilityReportAbuseControlPolicy(60, 10, "local", completeLimits(1));

		assertThat(policy.usesReleaseBlockingLocalStore()).isTrue();
	}

	@Test
	@DisplayName("abuse control policy는 실제 분산 store 구현 전까지 local mode만 허용한다")
	void policyRejectsNonLocalStoreMode() {
		assertThatThrownBy(() -> new FacilityReportAbuseControlPolicy(60, 10, "memory", completeLimits(1)))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("store mode");
		assertThatThrownBy(() -> new FacilityReportAbuseControlPolicy(60, 10, "distributed", completeLimits(1)))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("distributed store");
	}

	@Test
	@DisplayName("CIDR parser는 slash가 여러 개인 trusted proxy 값을 거부한다")
	void cidrParserRejectsMultipleSlashSyntax() {
		assertThatThrownBy(() -> IpCidr.parse("10.0.0.0/8/extra"))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("invalid IPv4 CIDR");
	}

	@Test
	@DisplayName("호출 한도 초과 시 429와 함께 Retry-After 및 Cache-Control no-store 헤더를 반환하고 본문은 비어 있다")
	void rateLimitedResponseIncludesRetryAfterAndNoStoreHeadersWithEmptyBody() throws Exception {
		createUploadIntent("abuse-retry-1", "198.51.100.99").andExpect(status().isCreated());
		createUploadIntent("abuse-retry-2", "198.51.100.99").andExpect(status().isCreated());

		createUploadIntent("abuse-retry-3", "198.51.100.99")
			.andExpect(status().isTooManyRequests())
			.andExpect(header().string("Cache-Control", containsString("no-store")))
			.andExpect(header().string("Retry-After", "60"));
	}

	@Test
	@DisplayName("직접 접속한 IPv6 client도 호출 한도에 걸리면 429를 반환한다")
	void directIpv6ClientIsRateLimited() throws Exception {
		createUploadIntent("abuse-ipv6-1", "2001:db8::10").andExpect(status().isCreated());
		createUploadIntent("abuse-ipv6-2", "2001:db8::10").andExpect(status().isCreated());

		createUploadIntent("abuse-ipv6-3", "2001:db8::10")
			.andExpect(status().isTooManyRequests())
			.andExpect(header().string("Retry-After", "60"));
	}

	@Test
	@DisplayName("trusted proxy 요청은 X-Forwarded-For의 IPv6 client IP로 한도를 분리한다")
	void trustedProxyForwardedIpv6ClientSeparatesRateLimitIdentity() throws Exception {
		getUnknownStatusFromForwardedClient("2001:db8:85a3::8a2e:370:7334").andExpect(status().isNotFound());
		getUnknownStatusFromForwardedClient("2001:db8:85a3::8a2e:370:7334").andExpect(status().isNotFound());

		getUnknownStatusFromForwardedClient("2001:db8:85a3::8a2e:370:7334")
			.andExpect(status().isTooManyRequests());

		getUnknownStatusFromForwardedClient("2001:db8:85a3::8a2e:370:7335")
			.andExpect(status().isNotFound());
	}

	@Test
	@DisplayName("IPv6 CIDR trusted proxy 파싱 및 일치 검사가 정상 동작한다")
	void ipv6CidrTrustedProxyParsesAndMatches() {
		var resolver = new FacilityReportClientIdentityResolver("2001:db8::/32, 10.0.0.0/8");
		org.springframework.mock.web.MockHttpServletRequest request = new org.springframework.mock.web.MockHttpServletRequest();
		request.setRemoteAddr("2001:db8::1");
		request.addHeader("X-Forwarded-For", "203.0.113.50");
		assertThat(resolver.resolve(request)).isEqualTo("ip:203.0.113.50");
	}

	@Test
	@DisplayName("IPv4-mapped IPv6 및 bracket/port 형태가 결정론적으로 정규화된다")
	void bracketAndPortAndIpv4MappedIpv6Normalized() {
		var resolver = new FacilityReportClientIdentityResolver("10.0.0.0/8");
		org.springframework.mock.web.MockHttpServletRequest request = new org.springframework.mock.web.MockHttpServletRequest();
		request.setRemoteAddr("10.0.0.1");
		request.addHeader("X-Forwarded-For", "[2001:db8::1]:8080");
		assertThat(resolver.resolve(request)).isEqualTo("ip:2001:db8:0:0:0:0:0:1");

		org.springframework.mock.web.MockHttpServletRequest request2 = new org.springframework.mock.web.MockHttpServletRequest();
		request2.setRemoteAddr("10.0.0.1");
		request2.addHeader("X-Forwarded-For", "::ffff:203.0.113.55");
		assertThat(resolver.resolve(request2)).isEqualTo("ip:203.0.113.55");
	}

	@Test
	@DisplayName("single-serving-replica storeMode 정책은 정상 허용된다")
	void singleServingReplicaStoreModeIsAllowed() {
		var policy = new FacilityReportAbuseControlPolicy(60, 10, "single-serving-replica", completeLimits(1));
		assertThat(policy.usesReleaseBlockingLocalStore()).isFalse();
	}

	private org.springframework.test.web.servlet.ResultActions createUploadIntent(String clientSubmissionId, String remoteAddr)
		throws Exception {
		return mockMvc.perform(post("/api/v1/report-uploads")
			.with(remoteAddr(remoteAddr))
			.contentType(MediaType.APPLICATION_JSON)
			.content("""
				{
				  "clientSubmissionId": "%s",
				  "photoFileName": "elevator.jpg",
				  "photoContentType": "image/jpeg",
				  "photoSha256": "2c8648d103e3dd7ad87660da0f126a1443b6d21ac1bd3ec000c5e24e2373a90c",
				  "photoSizeBytes": 11
				}
				""".formatted(clientSubmissionId)));
	}

	private org.springframework.test.web.servlet.ResultActions claimUpload(String uploadId, String remoteAddr) throws Exception {
		return mockMvc.perform(put("/api/v1/report-uploads/{uploadId}", uploadId)
			.with(remoteAddr(remoteAddr))
			.contentType(MediaType.IMAGE_JPEG)
			.content("image-bytes"));
	}

	private org.springframework.test.web.servlet.ResultActions createReceiptReport(String clientSubmissionId, String remoteAddr)
		throws Exception {
		return mockMvc.perform(post("/api/v1/reports")
			.with(remoteAddr(remoteAddr))
			.contentType(MediaType.APPLICATION_JSON)
			.content("""
				{
				  "clientSubmissionId": "%s",
				  "stationId": "station-sangnoksu",
				  "facilityId": "facility-sangnoksu-elevator-1",
				  "reportType": "BROKEN",
				  "description": "엘리베이터 문이 열리지 않습니다."
				}
				""".formatted(clientSubmissionId)));
	}

	private org.springframework.test.web.servlet.ResultActions getReceiptStatus(
		String reportId,
		String receiptToken,
		String remoteAddr
	)
		throws Exception {
		return mockMvc.perform(get("/api/v1/reports/{reportId}", reportId)
			.with(remoteAddr(remoteAddr))
			.header("X-Easysubway-Report-Receipt-Token", receiptToken));
	}

	private org.springframework.test.web.servlet.ResultActions confirmReceipt(String reportId, String remoteAddr) throws Exception {
		return mockMvc.perform(post("/api/v1/reports/{reportId}/confirm", reportId)
			.with(remoteAddr(remoteAddr))
			.with(csrf())
			.header("X-Easysubway-Report-Receipt-Token", "receipt-token-for-rate-limit"));
	}

	private org.springframework.test.web.servlet.ResultActions getUnknownStatusFromForwardedClient(String forwardedFor)
		throws Exception {
		return getUnknownStatusFromForwardedChain(forwardedFor);
	}

	private org.springframework.test.web.servlet.ResultActions getUnknownStatusFromForwardedChain(String forwardedFor)
		throws Exception {
		return mockMvc.perform(get("/api/v1/reports/unknown-forwarded-report")
			.with(remoteAddr("10.0.0.10"))
			.header("X-Forwarded-For", forwardedFor)
			.header("X-Easysubway-Report-Receipt-Token", "receipt-token-for-rate-limit"));
	}

	private org.springframework.test.web.servlet.ResultActions getUnknownStatusFromUntrustedForwardedClient(
		String remoteAddr,
		String forwardedFor
	)
		throws Exception {
		return mockMvc.perform(get("/api/v1/reports/unknown-untrusted-forwarded-report")
			.with(remoteAddr(remoteAddr))
			.header("X-Forwarded-For", forwardedFor)
			.header("X-Easysubway-Report-Receipt-Token", "receipt-token-for-rate-limit"));
	}

	@Test
	@DisplayName("CIDR 및 IP 파서의 에러 및 엣지 케이스가 규약대로 동작한다")
	void cidrAndIpParserEdgeCases() {
		// IpCidr.parse edge cases
		assertThatThrownBy(() -> IpCidr.parse(""))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> IpCidr.parse("/24"))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> IpCidr.parse("invalid-ip/24"))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> IpCidr.parse("10.0.0.0/abc"))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> IpCidr.parse("10.0.0.0/-1"))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> IpCidr.parse("10.0.0.0/33"))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> IpCidr.parse("2001:db8::/129"))
			.isInstanceOf(IllegalArgumentException.class);

		// IpCidr.parse without prefix defaults
		IpCidr ipv4Default = IpCidr.parse("192.168.1.1");
		assertThat(ipv4Default.prefixLength()).isEqualTo(32);
		IpCidr ipv6Default = IpCidr.parse("2001:db8::1");
		assertThat(ipv6Default.prefixLength()).isEqualTo(128);

		// IpCidr.contains edge cases
		IpCidr unaligned = IpCidr.parse("192.168.1.16/28");
		assertThat(unaligned.contains("192.168.1.20")).isTrue();
		assertThat(unaligned.contains("192.168.1.35")).isFalse();
		assertThat(unaligned.contains(null)).isFalse();
		assertThat(unaligned.contains("unknown")).isFalse();
		assertThat(unaligned.contains("2001:db8::1")).isFalse();
		assertThat(unaligned.contains("invalid-address")).isFalse();

		// IpCidr.isValidIp
		assertThat(IpCidr.isValidIp("192.168.1.1")).isTrue();
		assertThat(IpCidr.isValidIp("2001:db8::1")).isTrue();
		assertThat(IpCidr.isValidIp("invalid-ip")).isFalse();

		// normalizeAddress edge cases
		assertThat(FacilityReportClientIdentityResolver.normalizeAddress(null)).isEqualTo("unknown");
		assertThat(FacilityReportClientIdentityResolver.normalizeAddress("")).isEqualTo("unknown");
		assertThat(FacilityReportClientIdentityResolver.normalizeAddress("invalid-ip")).isEqualTo("unknown");

		// parseLiteral edge cases
		assertThatThrownBy(() -> FacilityReportClientIdentityResolver.parseLiteral(null))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> FacilityReportClientIdentityResolver.parseLiteral("   "))
			.isInstanceOf(IllegalArgumentException.class);
		assertThat(FacilityReportClientIdentityResolver.parseLiteral("192.168.1.1:8080").getHostAddress()).isEqualTo("192.168.1.1");
		assertThat(FacilityReportClientIdentityResolver.parseLiteral("fe80::1%eth0").getHostAddress()).contains("fe80:");
		assertThatThrownBy(() -> FacilityReportClientIdentityResolver.parseLiteral("2001:db8::invalid!"))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> FacilityReportClientIdentityResolver.parseLiteral("1:2:3:4:5:6:7:8:9"))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> FacilityReportClientIdentityResolver.parseLiteral("1.2.3"))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> FacilityReportClientIdentityResolver.parseLiteral("1.2.3.a"))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> FacilityReportClientIdentityResolver.parseLiteral("1.2.3.300"))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> FacilityReportClientIdentityResolver.parseLiteral("1.2.3.99999999999"))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> FacilityReportClientIdentityResolver.parseLiteral("1.2..4"))
			.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	@DisplayName("limiter의 maxCounterKeys 상한 초과 처리가 정상 동작한다")
	void limiterCapacityPressure() {
		var policy = new FacilityReportAbuseControlPolicy(
			60,
			1,
			"local",
			Map.of(
				ReportAbuseGroup.UPLOAD_INTENT, 1,
				ReportAbuseGroup.UPLOAD_CLAIM, 1,
				ReportAbuseGroup.REPORT_SUBMIT, 1,
				ReportAbuseGroup.STATUS, 1,
				ReportAbuseGroup.CONFIRM, 1
			)
		);
		var limiter = new FacilityReportAbuseControlLimiter(policy, Clock.fixed(Instant.parse("2026-06-22T00:00:00Z"), ZoneOffset.UTC));

		// first key allowed
		assertThat(limiter.acquire(ReportAbuseGroup.UPLOAD_INTENT, "client-1").allowed()).isTrue();
		// same key increments and hits limit
		assertThat(limiter.acquire(ReportAbuseGroup.UPLOAD_INTENT, "client-1").allowed()).isFalse();

		// second key exceeds maxCounterKeys (max is 1) -> counter == null -> returns limit
		var result = limiter.acquire(ReportAbuseGroup.UPLOAD_INTENT, "client-2");
		assertThat(result.allowed()).isFalse();
		assertThat(result.retryAfterSeconds()).isGreaterThanOrEqualTo(1);
	}

	@Test
	@DisplayName("abuse control 정책 생성 시 유효성 검증 예외를 던진다")
	void policyValidationEdgeCases() {
		assertThatThrownBy(() -> new FacilityReportAbuseControlPolicy(0, 1, "local", completeLimits(1)))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new FacilityReportAbuseControlPolicy(60, 0, "local", completeLimits(1)))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new FacilityReportAbuseControlPolicy(60, 1, "invalid-mode", completeLimits(1)))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new FacilityReportAbuseControlPolicy(60, 1, "local", Map.of(ReportAbuseGroup.UPLOAD_INTENT, 1)))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new FacilityReportAbuseControlPolicy(60, 1, "local", completeLimits(0)))
			.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	@DisplayName("윈도우 전환 시 카운터가 리셋되고 리졸버의 빈 프록시 설정이 처리된다")
	void limiterWindowRollAndResolverEdgeCases() {
		// Mock request non-matching route
		MockHttpServletRequest unmatchedRequest = new MockHttpServletRequest("GET", "/api/v1/unmatched");
		assertThat(ReportAbuseGroup.from(unmatchedRequest)).isEmpty();

		// Resolver with null and empty proxies
		FacilityReportClientIdentityResolver emptyResolver = new FacilityReportClientIdentityResolver("");
		assertThat(emptyResolver.resolve(unmatchedRequest)).isEqualTo("ip:127.0.0.1");

		FacilityReportClientIdentityResolver nullResolver = new FacilityReportClientIdentityResolver(null);
		assertThat(nullResolver.resolve(unmatchedRequest)).isEqualTo("ip:127.0.0.1");

		// Window rollover resets counter
		AtomicLong epochSeconds = new AtomicLong(1000);
		Clock mutableClock = new Clock() {
			@Override public ZoneId getZone() { return ZoneOffset.UTC; }
			@Override public Clock withZone(ZoneId zone) { return this; }
			@Override public Instant instant() { return Instant.ofEpochSecond(epochSeconds.get()); }
		};

		var policy = new FacilityReportAbuseControlPolicy(60, 10, "local", completeLimits(1));
		var limiter = new FacilityReportAbuseControlLimiter(policy, mutableClock);

		assertThat(limiter.acquire(ReportAbuseGroup.UPLOAD_INTENT, "client-1").allowed()).isTrue();
		assertThat(limiter.acquire(ReportAbuseGroup.UPLOAD_INTENT, "client-1").allowed()).isFalse();

		// Advance clock past the 60s window boundary
		epochSeconds.addAndGet(70);
		assertThat(limiter.acquire(ReportAbuseGroup.UPLOAD_INTENT, "client-1").allowed()).isTrue();
	}

	private static RequestPostProcessor remoteAddr(String remoteAddr) {
		return request -> {
			request.setRemoteAddr(remoteAddr);
			return request;
		};
	}

	private static Map<ReportAbuseGroup, Integer> completeLimits(int limit) {
		return Map.of(
			ReportAbuseGroup.UPLOAD_INTENT, limit,
			ReportAbuseGroup.UPLOAD_CLAIM, limit,
			ReportAbuseGroup.REPORT_SUBMIT, limit,
			ReportAbuseGroup.STATUS, limit,
			ReportAbuseGroup.CONFIRM, limit
		);
	}
}
