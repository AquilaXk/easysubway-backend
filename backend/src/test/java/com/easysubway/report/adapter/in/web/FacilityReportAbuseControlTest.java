package com.easysubway.report.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
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
			new FacilityReportAbuseControlPolicy(60, 2, completeLimits(100)),
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
			Map.of(ReportAbuseGroup.STATUS, 1)
		)).isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("UPLOAD_INTENT");

		assertThatThrownBy(() -> new FacilityReportAbuseControlPolicy(
			60,
			10,
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
	@DisplayName("CIDR parser는 slash가 여러 개인 trusted proxy 값을 거부한다")
	void cidrParserRejectsMultipleSlashSyntax() {
		assertThatThrownBy(() -> IpCidr.parse("10.0.0.0/8/extra"))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("invalid IPv4 CIDR");
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
