package com.easysubway.report.adapter.in.web;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {
	"easysubway.admin.username=admin-test",
	"easysubway.admin.password=admin-test-password",
	"easysubway.user.username=basic-user",
	"easysubway.user.password=user-test-password"
})
@AutoConfigureMockMvc
@DisplayName("시설 신고 API")
class FacilityReportControllerTest {

	private static final String VALID_PNG_BASE64 =
		"iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+/p9sAAAAASUVORK5CYII=";

	@Autowired
	private MockMvc mockMvc;

	@Test
	@DisplayName("시설 신고는 인증 사용자 기준으로 생성되고 같은 식별자로 조회한다")
	void createReportReturnsSubmittedReportAndCanBeRead() throws Exception {
		String response = mockMvc.perform(post("/api/v1/reports")
				.with(httpBasic("basic-user", "user-test-password"))
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "userId": "spoofed-user",
					  "stationId": "station-sangnoksu",
					  "facilityId": "facility-sangnoksu-elevator-1",
					  "reportType": "BROKEN",
					  "description": "엘리베이터 문이 열리지 않습니다.",
					  "latitude": 37.302421,
					  "longitude": 126.866221
					}
					"""))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.success").value(true))
			.andExpect(jsonPath("$.data.id").isNotEmpty())
			.andExpect(jsonPath("$.data.stationId").value("station-sangnoksu"))
			.andExpect(jsonPath("$.data.facilityId").value("facility-sangnoksu-elevator-1"))
			.andExpect(jsonPath("$.data.reportType").value("BROKEN"))
			.andExpect(jsonPath("$.data.status").value("SUBMITTED"))
			.andExpect(jsonPath("$.data.userId").doesNotExist())
			.andExpect(jsonPath("$.data.latitude").doesNotExist())
			.andExpect(jsonPath("$.data.longitude").doesNotExist())
			.andReturn()
			.getResponse()
			.getContentAsString();

		String reportId = JsonPath.read(response, "$.data.id");

		mockMvc.perform(get("/api/v1/reports/{reportId}", reportId)
				.with(httpBasic("basic-user", "user-test-password")))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.success").value(true))
			.andExpect(jsonPath("$.data.id").value(reportId))
			.andExpect(jsonPath("$.data.status").value("SUBMITTED"))
			.andExpect(jsonPath("$.data.userId").doesNotExist())
			.andExpect(jsonPath("$.data.latitude").doesNotExist())
			.andExpect(jsonPath("$.data.longitude").doesNotExist())
			.andExpect(jsonPath("$.data.reviewedBy").doesNotExist());
	}

	@Test
	@DisplayName("시설 신고는 receipt token으로 접수 상태 조회를 보호한다")
	void createReportReturnsReceiptTokenAndStatusRequiresReceiptToken() throws Exception {
		String response = mockMvc.perform(post("/api/v1/reports")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "clientSubmissionId": "client-submission-1",
					  "stationId": "station-sangnoksu",
					  "facilityId": "facility-sangnoksu-elevator-1",
					  "reportType": "BROKEN",
					  "description": "엘리베이터 문이 열리지 않습니다."
					}
					"""))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.success").value(true))
			.andExpect(jsonPath("$.data.id").isNotEmpty())
			.andExpect(jsonPath("$.data.publicReceiptCode").isNotEmpty())
			.andExpect(jsonPath("$.data.receiptToken").isNotEmpty())
			.andExpect(jsonPath("$.data.userId").doesNotExist())
			.andExpect(jsonPath("$.data.photoDataBase64").doesNotExist())
			.andReturn()
			.getResponse()
			.getContentAsString();

		String reportId = JsonPath.read(response, "$.data.id");
		String publicReceiptCode = JsonPath.read(response, "$.data.publicReceiptCode");
		String receiptToken = JsonPath.read(response, "$.data.receiptToken");

		mockMvc.perform(get("/api/v1/reports/{reportId}", reportId))
			.andExpect(status().isNotFound());

		mockMvc.perform(get("/api/v1/reports/{reportId}", reportId)
				.header("X-Easysubway-Report-Receipt-Token", receiptToken))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.success").value(true))
			.andExpect(jsonPath("$.data.id").value(reportId))
			.andExpect(jsonPath("$.data.publicReceiptCode").value(publicReceiptCode))
			.andExpect(jsonPath("$.data.receiptToken").doesNotExist())
			.andExpect(jsonPath("$.data.status").value("SUBMITTED"));
	}

	@Test
	@DisplayName("비인증 신고도 제출 식별자가 있으면 receipt token을 발급한다")
	void anonymousPrincipalReceiptSubmissionReturnsReceiptToken() throws Exception {
		String response = mockMvc.perform(post("/api/v1/reports")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "clientSubmissionId": "client-submission-anonymous-1",
					  "stationId": "station-sangnoksu",
					  "facilityId": "facility-sangnoksu-elevator-1",
					  "reportType": "BROKEN",
					  "description": "엘리베이터 문이 열리지 않습니다."
					}
					"""))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.success").value(true))
			.andExpect(jsonPath("$.data.id").isNotEmpty())
			.andExpect(jsonPath("$.data.receiptToken").isNotEmpty())
			.andReturn()
			.getResponse()
			.getContentAsString();

		String reportId = JsonPath.read(response, "$.data.id");
		String receiptToken = JsonPath.read(response, "$.data.receiptToken");

		mockMvc.perform(get("/api/v1/reports/{reportId}", reportId)
				.header("X-Easysubway-Report-Receipt-Token", receiptToken))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.success").value(true))
			.andExpect(jsonPath("$.data.id").value(reportId));
	}

	@Test
	@DisplayName("receipt token은 같은 제출 식별자 재시도 응답에서 다시 반환하지 않는다")
	void repeatedReceiptSubmissionDoesNotReturnPlainReceiptTokenAgain() throws Exception {
		String requestBody = """
			{
			  "clientSubmissionId": "client-submission-retry-1",
			  "stationId": "station-sangnoksu",
			  "facilityId": "facility-sangnoksu-elevator-1",
			  "reportType": "BROKEN",
			  "description": "재시도에서 token을 다시 받으면 안 되는 신고입니다."
			}
			""";
		String response = mockMvc.perform(post("/api/v1/reports")
				.contentType(MediaType.APPLICATION_JSON)
				.content(requestBody))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.data.id").isNotEmpty())
			.andExpect(jsonPath("$.data.receiptToken").isNotEmpty())
			.andReturn()
			.getResponse()
			.getContentAsString();

		String reportId = JsonPath.read(response, "$.data.id");

		mockMvc.perform(post("/api/v1/reports")
				.contentType(MediaType.APPLICATION_JSON)
				.content(requestBody))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.data.id").value(reportId))
			.andExpect(jsonPath("$.data.receiptToken").doesNotExist());
	}

	@Test
	@DisplayName("사진이 포함된 같은 제출 식별자 재시도는 기존 신고를 반환하고 새 pending object를 버린다")
	void repeatedPhotoReceiptSubmissionReturnsExistingReportAndDiscardsNewUpload() throws Exception {
		byte[] pngBytes = Base64.getDecoder().decode(VALID_PNG_BASE64);
		UploadedObject firstUpload = uploadReportPhotoObject(
			"client-submission-photo-retry-1",
			"image/png",
			pngBytes
		);
		String firstRequestBody = photoReceiptRequestBody(
			"client-submission-photo-retry-1",
			firstUpload.objectKey(),
			firstUpload.sha256(),
			pngBytes.length
		);
		String firstResponse = mockMvc.perform(post("/api/v1/reports")
				.contentType(MediaType.APPLICATION_JSON)
				.content(firstRequestBody))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.data.id").isNotEmpty())
			.andExpect(jsonPath("$.data.receiptToken").isNotEmpty())
			.andReturn()
			.getResponse()
			.getContentAsString();
		String reportId = JsonPath.read(firstResponse, "$.data.id");

		mockMvc.perform(post("/api/v1/reports")
				.contentType(MediaType.APPLICATION_JSON)
				.content(firstRequestBody))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.data.id").value(reportId))
			.andExpect(jsonPath("$.data.receiptToken").doesNotExist());

		UploadedObject secondUpload = uploadReportPhotoObject(
			"client-submission-photo-retry-1",
			"image/png",
			pngBytes
		);
		mockMvc.perform(post("/api/v1/reports")
				.contentType(MediaType.APPLICATION_JSON)
				.content(photoReceiptRequestBody(
					"client-submission-photo-retry-1",
					secondUpload.objectKey(),
					secondUpload.sha256(),
					pngBytes.length
				)))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.data.id").value(reportId))
			.andExpect(jsonPath("$.data.receiptToken").doesNotExist());

		mockMvc.perform(post("/api/v1/reports")
				.contentType(MediaType.APPLICATION_JSON)
				.content(photoReceiptRequestBody(
					"client-submission-photo-new-1",
					secondUpload.objectKey(),
					secondUpload.sha256(),
					pngBytes.length
				)))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.success").value(false))
			.andExpect(jsonPath("$.message").value("사진 첨부 정보를 확인해야 합니다."));
	}

	@Test
	@DisplayName("인증 사용자의 제출 식별자 신고는 내 신고로 유지된다")
	void authenticatedClientSubmissionRemainsUserReport() throws Exception {
		String requestBody = """
			{
			  "clientSubmissionId": "client-submission-auth-1",
			  "userId": "spoofed-user",
			  "stationId": "station-sangnoksu",
			  "facilityId": "facility-sangnoksu-elevator-1",
			  "reportType": "BROKEN",
			  "description": "엘리베이터 문이 열리지 않습니다."
			}
			""";
		String response = mockMvc.perform(post("/api/v1/reports")
				.with(httpBasic("basic-user", "user-test-password"))
				.contentType(MediaType.APPLICATION_JSON)
				.content(requestBody))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.success").value(true))
			.andExpect(jsonPath("$.data.id").isNotEmpty())
			.andExpect(jsonPath("$.data.receiptToken").doesNotExist())
			.andReturn()
			.getResponse()
			.getContentAsString();

		String reportId = JsonPath.read(response, "$.data.id");

		mockMvc.perform(post("/api/v1/reports")
				.with(httpBasic("basic-user", "user-test-password"))
				.contentType(MediaType.APPLICATION_JSON)
				.content(requestBody))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.data.id").value(reportId))
			.andExpect(jsonPath("$.data.receiptToken").doesNotExist());

		mockMvc.perform(get("/api/v1/reports/{reportId}", reportId)
				.with(httpBasic("basic-user", "user-test-password")))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.success").value(true))
			.andExpect(jsonPath("$.data.id").value(reportId));
	}

	@Test
	@DisplayName("시설 신고 생성은 인증된 사용자만 사용할 수 있다")
	void createReportRequiresAuthentication() throws Exception {
		mockMvc.perform(post("/api/v1/reports")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "userId": "anonymous-user-1",
					  "stationId": "station-sangnoksu",
					  "facilityId": "facility-sangnoksu-elevator-1",
					  "reportType": "BROKEN",
					  "description": "엘리베이터가 멈춰 있습니다."
					}
					"""))
			.andExpect(status().isUnauthorized());
	}

	@Test
	@DisplayName("존재하지 않는 시설 신고 요청은 공통 404 응답을 반환한다")
	void createReportReturnsCommonErrorForUnknownFacility() throws Exception {
		mockMvc.perform(post("/api/v1/reports")
				.with(httpBasic("basic-user", "user-test-password"))
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "userId": "anonymous-user-1",
					  "stationId": "station-sangnoksu",
					  "facilityId": "missing-facility",
					  "reportType": "BROKEN",
					  "description": "엘리베이터가 멈춰 있습니다."
					}
					"""))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.success").value(false))
			.andExpect(jsonPath("$.data").doesNotExist())
			.andExpect(jsonPath("$.message").value("시설 정보를 찾을 수 없습니다."));
	}

	@Test
	@DisplayName("신고 유형이 없는 요청은 공통 400 응답을 반환한다")
	void createReportReturnsCommonErrorForMissingReportType() throws Exception {
		mockMvc.perform(post("/api/v1/reports")
				.with(httpBasic("basic-user", "user-test-password"))
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "userId": "anonymous-user-1",
					  "stationId": "station-sangnoksu",
					  "facilityId": "facility-sangnoksu-elevator-1",
					  "description": "신고 유형이 없는 요청입니다."
					}
					"""))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.success").value(false))
			.andExpect(jsonPath("$.data").doesNotExist())
			.andExpect(jsonPath("$.message").value("신고 유형을 선택해야 합니다."));
	}

	@Test
	@DisplayName("알 수 없는 신고 유형은 공통 400 응답을 반환한다")
	void createReportReturnsCommonErrorForInvalidReportType() throws Exception {
		mockMvc.perform(post("/api/v1/reports")
				.with(httpBasic("basic-user", "user-test-password"))
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "userId": "anonymous-user-1",
					  "stationId": "station-sangnoksu",
					  "facilityId": "facility-sangnoksu-elevator-1",
					  "reportType": "BROKNN",
					  "description": "잘못된 신고 유형입니다."
					}
					"""))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.success").value(false))
			.andExpect(jsonPath("$.data").doesNotExist())
			.andExpect(jsonPath("$.message").value("요청 본문을 확인해야 합니다."));
	}

	@Test
	@DisplayName("존재하지 않는 신고 조회는 공통 404 응답을 반환한다")
	void missingReportReturnsCommonErrorResponse() throws Exception {
		mockMvc.perform(get("/api/v1/reports/missing-report")
				.with(httpBasic("basic-user", "user-test-password")))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.success").value(false))
			.andExpect(jsonPath("$.data").doesNotExist())
			.andExpect(jsonPath("$.message").value("신고 정보를 찾을 수 없습니다."));
	}

	@Test
	@DisplayName("신고 상세 조회는 인증된 신고 소유자만 사용할 수 있다")
	void reportDetailRequiresAuthenticationAndOwner() throws Exception {
		String reportId = createReport("basic-user", "user-test-password", "spoofed-user", "소유자만 볼 수 있는 신고");

		mockMvc.perform(get("/api/v1/reports/{reportId}", reportId))
			.andExpect(status().isNotFound());

		mockMvc.perform(get("/api/v1/reports/{reportId}", reportId)
				.with(httpBasic("admin-test", "admin-test-password")))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.success").value(false))
			.andExpect(jsonPath("$.data").doesNotExist())
			.andExpect(jsonPath("$.message").value("신고 정보를 찾을 수 없습니다."));
	}

	@Test
	@DisplayName("관리자 신고 목록은 상세 사진 메타데이터 없이 첨부 여부만 반환한다")
	void reportListsReturnPhotoSummaryWithoutDetailMetadata() throws Exception {
		createReportWithPhoto("basic-user", "user-test-password", "spoofed-user", "사진이 있는 신고");

		String adminReportsResponse = mockMvc.perform(get("/admin/reports")
				.with(httpBasic("admin-test", "admin-test-password")))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.success").value(true))
			.andReturn()
			.getResponse()
			.getContentAsString();

		Assertions.assertThat(adminReportsResponse)
			.contains("hasPhoto")
			.contains("publicReceiptCode")
			.doesNotContain("photoFileName")
			.doesNotContain("photoContentType")
			.doesNotContain("photoObjectKey")
			.doesNotContain("photoSha256")
			.doesNotContain("photoSizeBytes")
			.doesNotContain("photoDataBase64");
	}

	@Test
	@DisplayName("관리자는 신고를 승인하고 조회 결과에서 검수 상태를 확인할 수 있다")
	void reviewReportStoresAcceptedStatusAndCanBeRead() throws Exception {
		String response = mockMvc.perform(post("/api/v1/reports")
				.with(httpBasic("basic-user", "user-test-password"))
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "userId": "anonymous-user-review",
					  "stationId": "station-sangnoksu",
					  "facilityId": "facility-sangnoksu-elevator-1",
					  "reportType": "BROKEN",
					  "description": "엘리베이터 문이 열리지 않습니다."
					}
					"""))
			.andExpect(status().isCreated())
			.andReturn()
			.getResponse()
			.getContentAsString();

		String reportId = JsonPath.read(response, "$.data.id");

		mockMvc.perform(post("/admin/reports/{reportId}/review", reportId)
				.with(httpBasic("admin-test", "admin-test-password"))
				.with(csrf())
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "decision": "ACCEPT"
					}
					"""))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.success").value(true))
			.andExpect(jsonPath("$.data.id").value(reportId))
			.andExpect(jsonPath("$.data.status").value("ACCEPTED"))
			.andExpect(jsonPath("$.data.reviewedAt").isNotEmpty())
			.andExpect(jsonPath("$.data.reviewedBy").value("admin-test"));

		mockMvc.perform(get("/api/v1/reports/{reportId}", reportId)
				.with(httpBasic("basic-user", "user-test-password")))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.status").value("ACCEPTED"))
			.andExpect(jsonPath("$.data.reviewedBy").doesNotExist());

		mockMvc.perform(get("/admin/stations/station-sangnoksu")
				.with(httpBasic("admin-test", "admin-test-password")))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.facilities[0].id").value("facility-sangnoksu-elevator-1"))
			.andExpect(jsonPath("$.data.facilities[0].status").value("BROKEN"));
	}

	@Test
	@DisplayName("이미 검수된 신고 재검수는 공통 409 응답을 반환한다")
	void repeatedReviewReportReturnsCommonConflictError() throws Exception {
		String reportId = createReport("basic-user", "user-test-password", "spoofed-user", "이미 검수된 신고");
		mockMvc.perform(post("/admin/reports/{reportId}/review", reportId)
				.with(httpBasic("admin-test", "admin-test-password"))
				.with(csrf())
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "decision": "REJECT"
					}
					"""))
			.andExpect(status().isOk());

		mockMvc.perform(post("/admin/reports/{reportId}/review", reportId)
				.with(httpBasic("admin-test", "admin-test-password"))
				.with(csrf())
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "decision": "REJECT"
					}
					"""))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.success").value(false))
			.andExpect(jsonPath("$.data").doesNotExist())
			.andExpect(jsonPath("$.message").value("이미 확인 처리된 신고입니다."));
	}

	@Test
	@DisplayName("신고 작성자는 처리 결과를 확인 완료 상태로 바꿀 수 있다")
	void reporterConfirmsReviewedReportResult() throws Exception {
		String reportId = createReport("basic-user", "user-test-password", "spoofed-user", "처리 결과를 확인할 신고");

		mockMvc.perform(post("/admin/reports/{reportId}/review", reportId)
				.with(httpBasic("admin-test", "admin-test-password"))
				.with(csrf())
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "decision": "REJECT"
					}
					"""))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.status").value("REJECTED"));

		mockMvc.perform(post("/api/v1/reports/{reportId}/confirm", reportId)
				.with(httpBasic("basic-user", "user-test-password"))
				.with(csrf()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.success").value(true))
			.andExpect(jsonPath("$.data.id").value(reportId))
			.andExpect(jsonPath("$.data.status").value("RESOLVED"))
			.andExpect(jsonPath("$.data.reviewedBy").doesNotExist());
	}

	@Test
	@DisplayName("receipt token 신고는 계정 없이 처리 결과를 확인 완료 상태로 바꾼다")
	void receiptTokenConfirmsReviewedReportResultWithoutAccount() throws Exception {
		String response = mockMvc.perform(post("/api/v1/reports")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "clientSubmissionId": "client-submission-confirm-1",
					  "stationId": "station-sangnoksu",
					  "facilityId": "facility-sangnoksu-elevator-1",
					  "reportType": "BROKEN",
					  "description": "receipt token으로 확인할 신고입니다."
					}
					"""))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.data.receiptToken").isNotEmpty())
			.andReturn()
			.getResponse()
			.getContentAsString();

		String reportId = JsonPath.read(response, "$.data.id");
		String receiptToken = JsonPath.read(response, "$.data.receiptToken");

		mockMvc.perform(post("/admin/reports/{reportId}/review", reportId)
				.with(httpBasic("admin-test", "admin-test-password"))
				.with(csrf())
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "decision": "REJECT"
					}
					"""))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.status").value("REJECTED"));

		mockMvc.perform(post("/api/v1/reports/{reportId}/confirm", reportId)
				.with(csrf())
				.header("X-Easysubway-Report-Receipt-Token", receiptToken))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.success").value(true))
			.andExpect(jsonPath("$.data.id").value(reportId))
			.andExpect(jsonPath("$.data.status").value("RESOLVED"))
			.andExpect(jsonPath("$.data.receiptToken").doesNotExist())
			.andExpect(jsonPath("$.data.reviewedBy").doesNotExist());
	}

	@Test
	@DisplayName("receipt token은 다른 신고의 상태 조회와 확인 요청에 재사용할 수 없다")
	void receiptTokenCannotBeReusedAcrossReportsForStatusOrConfirm() throws Exception {
		ReceiptReport firstReport = createAnonymousReceiptReport(
			"client-submission-token-abuse-1",
			"첫 번째 receipt token abuse 검증 신고"
		);
		ReceiptReport secondReport = createAnonymousReceiptReport(
			"client-submission-token-abuse-2",
			"두 번째 receipt token abuse 검증 신고"
		);

		mockMvc.perform(post("/admin/reports/{reportId}/review", firstReport.reportId())
				.with(httpBasic("admin-test", "admin-test-password"))
				.with(csrf())
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "decision": "REJECT"
					}
					"""))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.status").value("REJECTED"));

		String statusResponse = mockMvc.perform(get("/api/v1/reports/{reportId}", firstReport.reportId())
				.header("X-Easysubway-Report-Receipt-Token", secondReport.receiptToken()))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.success").value(false))
			.andExpect(jsonPath("$.message").value("신고 정보를 찾을 수 없습니다."))
			.andReturn()
			.getResponse()
			.getContentAsString();
		Assertions.assertThat(statusResponse)
			.doesNotContain(firstReport.receiptToken())
			.doesNotContain(secondReport.receiptToken());

		String confirmResponse = mockMvc.perform(post("/api/v1/reports/{reportId}/confirm", firstReport.reportId())
				.with(csrf())
				.header("X-Easysubway-Report-Receipt-Token", secondReport.receiptToken()))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.success").value(false))
			.andExpect(jsonPath("$.message").value("신고 정보를 찾을 수 없습니다."))
			.andReturn()
			.getResponse()
			.getContentAsString();
		Assertions.assertThat(confirmResponse)
			.doesNotContain(firstReport.receiptToken())
			.doesNotContain(secondReport.receiptToken());
	}

	@Test
	@DisplayName("다른 사용자의 신고 처리 결과 확인은 공통 404 응답을 반환한다")
	void confirmReportResultRequiresOwner() throws Exception {
		String reportId = createReport("basic-user", "user-test-password", "spoofed-user", "다른 사용자가 확인하면 안 되는 신고");

		mockMvc.perform(post("/admin/reports/{reportId}/review", reportId)
				.with(httpBasic("admin-test", "admin-test-password"))
				.with(csrf())
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "decision": "REJECT"
					}
					"""))
			.andExpect(status().isOk());

		mockMvc.perform(post("/api/v1/reports/{reportId}/confirm", reportId)
				.with(httpBasic("admin-test", "admin-test-password"))
				.with(csrf()))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.success").value(false))
			.andExpect(jsonPath("$.data").doesNotExist())
			.andExpect(jsonPath("$.message").value("신고 정보를 찾을 수 없습니다."));
	}

	@Test
	@DisplayName("검수 전 신고 처리 결과 확인은 공통 400 응답을 반환한다")
	void confirmReportResultRequiresReviewedStatus() throws Exception {
		String reportId = createReport("basic-user", "user-test-password", "spoofed-user", "아직 검수되지 않은 신고");

		mockMvc.perform(post("/api/v1/reports/{reportId}/confirm", reportId)
				.with(httpBasic("basic-user", "user-test-password"))
				.with(csrf()))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.success").value(false))
			.andExpect(jsonPath("$.data").doesNotExist())
			.andExpect(jsonPath("$.message").value("처리가 끝난 신고만 확인할 수 있습니다."));
	}

	@Test
	@DisplayName("신고 검수는 관리자 인증을 요구한다")
	void reviewReportRequiresAdminAuthentication() throws Exception {
		mockMvc.perform(post("/admin/reports/report-1/review")
				.with(csrf())
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "decision": "ACCEPT"
					}
					"""))
			.andExpect(status().isUnauthorized());
	}

	@Test
	@DisplayName("검수 결정값이 없는 요청은 공통 400 응답을 반환한다")
	void reviewReportRejectsInvalidDecisionRequest() throws Exception {
		mockMvc.perform(post("/admin/reports/report-1/review")
				.with(httpBasic("admin-test", "admin-test-password"))
				.with(csrf())
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{}
					"""))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.success").value(false))
			.andExpect(jsonPath("$.data").doesNotExist())
			.andExpect(jsonPath("$.message").value("확인 결과를 선택해야 합니다."));
	}

	@Test
	@DisplayName("관리자는 중복 신고를 기준 신고와 함께 처리한다")
	void reviewReportMarksDuplicateWithOriginalReport() throws Exception {
		String originalReportId = createReport("anonymous-user-original", "먼저 접수된 고장 신고");
		String duplicatedReportId = createReport("anonymous-user-duplicated", "같은 시설에 대해 다시 들어온 신고");

		mockMvc.perform(post("/admin/reports/{reportId}/review", duplicatedReportId)
				.with(httpBasic("admin-test", "admin-test-password"))
				.with(csrf())
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "decision": "MARK_DUPLICATE",
					  "duplicateOfReportId": "%s"
					}
					""".formatted(originalReportId)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.success").value(true))
			.andExpect(jsonPath("$.data.id").value(duplicatedReportId))
			.andExpect(jsonPath("$.data.status").value("DUPLICATE"))
			.andExpect(jsonPath("$.data.duplicateOfReportId").value(originalReportId));

		mockMvc.perform(get("/admin/reports/{reportId}", duplicatedReportId)
				.with(httpBasic("admin-test", "admin-test-password")))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.duplicateOfReportId").value(originalReportId));
	}

	@Test
	@DisplayName("중복 신고 검수는 기준 신고 식별자를 요구한다")
	void duplicateReviewRequiresOriginalReportId() throws Exception {
		String duplicatedReportId = createReport("anonymous-user-duplicated", "기준 신고 없이 중복 처리할 신고");

		mockMvc.perform(post("/admin/reports/{reportId}/review", duplicatedReportId)
				.with(httpBasic("admin-test", "admin-test-password"))
				.with(csrf())
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "decision": "MARK_DUPLICATE"
					}
					"""))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.success").value(false))
			.andExpect(jsonPath("$.data").doesNotExist())
			.andExpect(jsonPath("$.message").value("기준 신고를 확인해야 합니다."));
	}

	@Test
	@DisplayName("존재하지 않는 신고 검수는 공통 404 응답을 반환한다")
	void reviewReportReturnsCommonErrorForMissingReport() throws Exception {
		mockMvc.perform(post("/admin/reports/missing-report/review")
				.with(httpBasic("admin-test", "admin-test-password"))
				.with(csrf())
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "decision": "REJECT"
					}
					"""))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.success").value(false))
			.andExpect(jsonPath("$.data").doesNotExist())
			.andExpect(jsonPath("$.message").value("신고 정보를 찾을 수 없습니다."));
	}

	@Test
	@DisplayName("관리자는 신고 목록을 최신순으로 조회하고 상태별로 좁혀 본다")
	void adminListsReportsByNewestFirstAndStatus() throws Exception {
		String submittedReportId = createReport("anonymous-user-list-submitted", "승강기 문이 닫히지 않습니다.");
		String acceptedReportId = createReport("anonymous-user-list-accepted", "엘리베이터 위치가 다릅니다.");

		mockMvc.perform(post("/admin/reports/{reportId}/review", acceptedReportId)
				.with(httpBasic("admin-test", "admin-test-password"))
				.with(csrf())
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "decision": "ACCEPT"
					}
					"""))
			.andExpect(status().isOk());

		String listResponse = mockMvc.perform(get("/admin/reports")
				.with(httpBasic("admin-test", "admin-test-password")))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.success").value(true))
			.andExpect(jsonPath("$.data.items").isArray())
			.andReturn()
			.getResponse()
			.getContentAsString();

		List<String> reportIds = JsonPath.read(listResponse, "$.data.items[*].id");
		Assertions.assertThat(reportIds)
			.contains(acceptedReportId, submittedReportId);
		Assertions.assertThat(reportIds.indexOf(acceptedReportId))
			.isLessThan(reportIds.indexOf(submittedReportId));

		String submittedOnlyResponse = mockMvc.perform(get("/admin/reports")
				.queryParam("status", "SUBMITTED")
				.with(httpBasic("admin-test", "admin-test-password")))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.success").value(true))
			.andReturn()
			.getResponse()
			.getContentAsString();

		List<String> submittedIds = JsonPath.read(submittedOnlyResponse, "$.data.items[*].id");
		Assertions.assertThat(submittedIds).contains(submittedReportId);
		Assertions.assertThat(submittedIds).doesNotContain(acceptedReportId);
	}

	@Test
	@DisplayName("관리자 신고 목록은 page size를 상한으로 제한한다")
	void adminReportListCapsRequestedPageSize() throws Exception {
		for (int index = 0; index < 55; index++) {
			createReport("anonymous-user-cap-" + index, "상한 검증 신고 " + index);
		}

		String response = mockMvc.perform(get("/admin/reports")
				.queryParam("size", "500")
				.with(httpBasic("admin-test", "admin-test-password")))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.size").value(50))
			.andExpect(jsonPath("$.data.hasNext").value(true))
			.andReturn()
			.getResponse()
			.getContentAsString();

		List<String> reportIds = JsonPath.read(response, "$.data.items[*].id");
		Assertions.assertThat(reportIds).hasSize(50);
	}

	@Test
	@DisplayName("관리자 신고 목록은 관리자만 사용할 수 있다")
	void adminReportListRequiresAdminAuthentication() throws Exception {
		mockMvc.perform(get("/admin/reports"))
			.andExpect(status().isUnauthorized());

		mockMvc.perform(get("/admin/reports")
				.with(httpBasic("basic-user", "user-test-password")))
			.andExpect(status().isForbidden());
	}

	@Test
	@DisplayName("관리자 신고 목록은 잘못된 상태 필터를 공통 오류 형식으로 응답한다")
	void adminReportListRejectsInvalidStatusWithCommonErrorResponse() throws Exception {
		mockMvc.perform(get("/admin/reports")
				.queryParam("status", "submitted")
				.with(httpBasic("admin-test", "admin-test-password")))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.success").value(false))
			.andExpect(jsonPath("$.data").doesNotExist())
			.andExpect(jsonPath("$.message").value("요청 값을 확인해야 합니다."));
	}

	@Test
	@DisplayName("관리자는 신고 상세에서 사진 첨부와 위치 정보를 확인한다")
	void adminReadsReportDetailWithPhotoAndLocation() throws Exception {
		String response = mockMvc.perform(post("/api/v1/reports")
				.with(httpBasic("basic-user", "user-test-password"))
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "userId": "anonymous-user-admin-detail",
					  "stationId": "station-sangnoksu",
					  "facilityId": "facility-sangnoksu-elevator-1",
					  "reportType": "BROKEN",
					  "description": "엘리베이터 앞 안내문이 떨어져 있습니다.",
						  "photoFileName": "elevator-notice.png",
						  "photoContentType": "image/png",
						  "photoDataBase64": "%s",
					  "latitude": 37.302421,
					  "longitude": 126.866221
						}
						""".formatted(VALID_PNG_BASE64)))
			.andExpect(status().isCreated())
			.andReturn()
			.getResponse()
			.getContentAsString();

		String reportId = JsonPath.read(response, "$.data.id");
		String publicReceiptCode = JsonPath.read(response, "$.data.publicReceiptCode");
		Assertions.assertThat(response)
			.doesNotContain("photoDataBase64")
			.doesNotContain("aW1hZ2UtYnl0ZXM=");

		mockMvc.perform(get("/admin/reports/{reportId}", reportId)
				.with(httpBasic("admin-test", "admin-test-password")))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.success").value(true))
			.andExpect(jsonPath("$.data.id").value(reportId))
			.andExpect(jsonPath("$.data.publicReceiptCode").value(publicReceiptCode))
			.andExpect(jsonPath("$.data.userId").value("basic-user"))
			.andExpect(jsonPath("$.data.description").value("엘리베이터 앞 안내문이 떨어져 있습니다."))
			.andExpect(jsonPath("$.data.photoFileName").value("elevator-notice.png"))
			.andExpect(jsonPath("$.data.photoContentType").value("image/png"))
			.andExpect(jsonPath("$.data.photoObjectKey").isNotEmpty())
			.andExpect(jsonPath("$.data.photoThumbnailObjectKey").isNotEmpty())
			.andExpect(jsonPath("$.data.photoSha256").isNotEmpty())
			.andExpect(jsonPath("$.data.photoSizeBytes").isNumber())
			.andExpect(jsonPath("$.data.photoDataBase64").doesNotExist())
			.andExpect(jsonPath("$.data.photoUrl").doesNotExist())
			.andExpect(jsonPath("$.data.latitude").value(37.302421))
			.andExpect(jsonPath("$.data.longitude").value(126.866221))
			.andExpect(jsonPath("$.data.status").value("SUBMITTED"));
	}

	@Test
	@DisplayName("사용자 신고 상세는 사진과 위치 메타데이터를 반환하지 않는다")
	void userReportDetailDoesNotReturnPhotoOrLocationMetadata() throws Exception {
		String reportId = createReportWithPhoto("basic-user", "user-test-password", "spoofed-user", "사진이 있는 신고");

		String response = mockMvc.perform(get("/api/v1/reports/{reportId}", reportId)
				.with(httpBasic("basic-user", "user-test-password")))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.success").value(true))
			.andExpect(jsonPath("$.data.photoFileName").doesNotExist())
			.andExpect(jsonPath("$.data.photoContentType").doesNotExist())
			.andExpect(jsonPath("$.data.latitude").doesNotExist())
			.andExpect(jsonPath("$.data.longitude").doesNotExist())
			.andReturn()
			.getResponse()
			.getContentAsString();

		Assertions.assertThat(response)
			.doesNotContain("basic-user")
				.doesNotContain("elevator-notice.png")
				.doesNotContain("image/png")
				.doesNotContain("photoDataBase64")
				.doesNotContain(VALID_PNG_BASE64);
	}

	@Test
	@DisplayName("사진 형식이 잘못된 신고 생성은 공통 400 응답을 반환한다")
	void createReportRejectsUnsupportedPhotoContentType() throws Exception {
		mockMvc.perform(post("/api/v1/reports")
				.with(httpBasic("basic-user", "user-test-password"))
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "userId": "spoofed-user",
					  "stationId": "station-sangnoksu",
					  "facilityId": "facility-sangnoksu-elevator-1",
					  "reportType": "BROKEN",
					  "description": "이미지 형식이 아닌 파일입니다.",
					  "photoFileName": "memo.txt",
					  "photoContentType": "text/plain",
					  "photoDataBase64": "aW1hZ2UtYnl0ZXM="
					}
					"""))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.success").value(false))
			.andExpect(jsonPath("$.message").value("사진 파일 형식을 확인해야 합니다."));
	}

	@Test
	@DisplayName("신고 사진 업로드는 intent 검증 header가 없으면 거부한다")
	void uploadReportPhotoRequiresIntentValidationHeaders() throws Exception {
		String intentResponse = mockMvc.perform(post("/api/v1/report-uploads")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "clientSubmissionId": "client-submission-upload-headers-1",
					  "photoFileName": "elevator.jpg",
					  "photoContentType": "image/jpeg",
					  "photoSha256": "2c8648d103e3dd7ad87660da0f126a1443b6d21ac1bd3ec000c5e24e2373a90c",
					  "photoSizeBytes": 11
					}
					"""))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.data.uploadHeaders['x-easysubway-upload-sha256']").value(
				"2c8648d103e3dd7ad87660da0f126a1443b6d21ac1bd3ec000c5e24e2373a90c"
			))
			.andExpect(jsonPath("$.data.uploadHeaders['x-easysubway-upload-size']").value("11"))
			.andExpect(jsonPath("$.data.uploadHeaders['content-type']").value("image/jpeg"))
			.andReturn()
			.getResponse()
			.getContentAsString();

		String uploadUrl = JsonPath.read(intentResponse, "$.data.uploadUrl");

		mockMvc.perform(put(uploadUrl)
				.contentType(MediaType.IMAGE_JPEG)
				.content("image-bytes"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.success").value(false))
			.andExpect(jsonPath("$.message").value("사진 첨부 정보를 확인해야 합니다."));
	}

	@Test
	@DisplayName("신고 사진 업로드는 intent의 사진 형식과 다른 Content-Type을 거부한다")
	void uploadReportPhotoRejectsMismatchedIntentContentType() throws Exception {
		String intentResponse = mockMvc.perform(post("/api/v1/report-uploads")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "clientSubmissionId": "client-submission-upload-content-type-1",
					  "photoFileName": "elevator.jpg",
					  "photoContentType": "image/jpeg",
					  "photoSha256": "2c8648d103e3dd7ad87660da0f126a1443b6d21ac1bd3ec000c5e24e2373a90c",
					  "photoSizeBytes": 11
					}
					"""))
			.andExpect(status().isCreated())
			.andReturn()
			.getResponse()
			.getContentAsString();

		String uploadUrl = JsonPath.read(intentResponse, "$.data.uploadUrl");

		mockMvc.perform(put(uploadUrl)
				.contentType(MediaType.IMAGE_PNG)
				.header("x-easysubway-upload-sha256", "2c8648d103e3dd7ad87660da0f126a1443b6d21ac1bd3ec000c5e24e2373a90c")
				.header("x-easysubway-upload-size", "11")
				.content("image-bytes"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.success").value(false))
			.andExpect(jsonPath("$.message").value("사진 첨부 정보를 확인해야 합니다."));
	}

	@Test
	@DisplayName("사진 object key 신고 생성은 발급된 pending upload intent를 요구한다")
	void createReportRequiresPendingUploadIntentForPhotoObjectKey() throws Exception {
		mockMvc.perform(post("/api/v1/reports")
				.with(httpBasic("basic-user", "user-test-password"))
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "userId": "spoofed-user",
					  "stationId": "station-sangnoksu",
					  "facilityId": "facility-sangnoksu-elevator-1",
					  "reportType": "BROKEN",
					  "description": "발급되지 않은 사진 객체 신고입니다.",
					  "photoFileName": "elevator.jpg",
					  "photoContentType": "image/jpeg",
					  "photoObjectKey": "facility-reports/report-existing/final-photo.jpg",
					  "photoSha256": "2c8648d103e3dd7ad87660da0f126a1443b6d21ac1bd3ec000c5e24e2373a90c",
					  "photoSizeBytes": 11
					}
					"""))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.success").value(false))
			.andExpect(jsonPath("$.message").value("사진 첨부 정보를 확인해야 합니다."));
	}

	@Test
	@DisplayName("사진 크기가 큰 신고 생성은 공통 400 응답을 반환한다")
	void createReportRejectsLargePhotoPayload() throws Exception {
		String largePhotoBase64 = Base64.getEncoder().encodeToString(new byte[(900 * 1024) + 1]);

		mockMvc.perform(post("/api/v1/reports")
				.with(httpBasic("basic-user", "user-test-password"))
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "userId": "spoofed-user",
					  "stationId": "station-sangnoksu",
					  "facilityId": "facility-sangnoksu-elevator-1",
					  "reportType": "BROKEN",
					  "description": "사진이 너무 큰 신고입니다.",
					  "photoFileName": "large.jpg",
					  "photoContentType": "image/jpeg",
					  "photoDataBase64": "%s"
					}
					""".formatted(largePhotoBase64)))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.success").value(false))
			.andExpect(jsonPath("$.message").value("사진 파일 크기를 줄여야 합니다."));
	}

	@Test
	@DisplayName("관리자 신고 상세는 관리자만 사용할 수 있다")
	void adminReportDetailRequiresAdminAuthentication() throws Exception {
		mockMvc.perform(get("/admin/reports/report-1"))
			.andExpect(status().isUnauthorized());

		mockMvc.perform(get("/admin/reports/report-1")
				.with(httpBasic("basic-user", "user-test-password")))
			.andExpect(status().isForbidden());
	}

	@Test
	@DisplayName("존재하지 않는 관리자 신고 상세는 공통 404 응답을 반환한다")
	void adminReportDetailReturnsCommonErrorForMissingReport() throws Exception {
		mockMvc.perform(get("/admin/reports/missing-report")
				.with(httpBasic("admin-test", "admin-test-password")))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.success").value(false))
			.andExpect(jsonPath("$.data").doesNotExist())
			.andExpect(jsonPath("$.message").value("신고 정보를 찾을 수 없습니다."));
	}

	private String createReport(String userId, String description) throws Exception {
		return createReport("basic-user", "user-test-password", userId, description);
	}

	private String createReport(
		String username,
		String password,
		String userId,
		String description
	) throws Exception {
		return createReport(username, password, userId, description, "");
	}

	private String createReportWithPhoto(
		String username,
		String password,
		String userId,
		String description
	) throws Exception {
		return createReport(
			username,
			password,
			userId,
			description,
			"""
				,
						  "photoFileName": "elevator-notice.png",
						  "photoContentType": "image/png",
						  "photoDataBase64": "%s"
					"""
					.formatted(VALID_PNG_BASE64)
			);
	}

	private String createReport(
		String username,
		String password,
		String userId,
		String description,
		String photoJson
	) throws Exception {
		String response = mockMvc.perform(post("/api/v1/reports")
				.with(httpBasic(username, password))
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "userId": "%s",
					  "stationId": "station-sangnoksu",
					  "facilityId": "facility-sangnoksu-elevator-1",
					  "reportType": "BROKEN",
					  "description": "%s"
					  %s
					}
					""".formatted(userId, description, photoJson)))
			.andExpect(status().isCreated())
			.andReturn()
			.getResponse()
			.getContentAsString();

		return JsonPath.read(response, "$.data.id");
	}

	private ReceiptReport createAnonymousReceiptReport(String clientSubmissionId, String description) throws Exception {
		String response = mockMvc.perform(post("/api/v1/reports")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "clientSubmissionId": "%s",
					  "stationId": "station-sangnoksu",
					  "facilityId": "facility-sangnoksu-elevator-1",
					  "reportType": "BROKEN",
					  "description": "%s"
					}
					""".formatted(clientSubmissionId, description)))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.data.receiptToken").isNotEmpty())
			.andReturn()
			.getResponse()
			.getContentAsString();

		return new ReceiptReport(
			JsonPath.read(response, "$.data.id"),
			JsonPath.read(response, "$.data.receiptToken")
		);
	}

	private UploadedObject uploadReportPhotoObject(
		String clientSubmissionId,
		String contentType,
		byte[] bytes
	) throws Exception {
		String sha256 = sha256Hex(bytes);
		String intentResponse = mockMvc.perform(post("/api/v1/report-uploads")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "clientSubmissionId": "%s",
					  "photoFileName": "elevator.png",
					  "photoContentType": "%s",
					  "photoSha256": "%s",
					  "photoSizeBytes": %d
					}
					""".formatted(clientSubmissionId, contentType, sha256, bytes.length)))
			.andExpect(status().isCreated())
			.andReturn()
			.getResponse()
			.getContentAsString();
		String uploadUrl = JsonPath.read(intentResponse, "$.data.uploadUrl");
		String objectKey = JsonPath.read(intentResponse, "$.data.objectKey");

		mockMvc.perform(put(uploadUrl)
				.header("Content-Type", contentType)
				.header("x-easysubway-upload-sha256", sha256)
				.header("x-easysubway-upload-size", String.valueOf(bytes.length))
				.content(bytes))
			.andExpect(status().isNoContent());

		return new UploadedObject(objectKey, sha256);
	}

	private String photoReceiptRequestBody(
		String clientSubmissionId,
		String objectKey,
		String sha256,
		int sizeBytes
	) {
		return """
			{
			  "clientSubmissionId": "%s",
			  "stationId": "station-sangnoksu",
			  "facilityId": "facility-sangnoksu-elevator-1",
			  "reportType": "BROKEN",
			  "description": "사진이 포함된 재시도 신고입니다.",
			  "photoFileName": "elevator.png",
			  "photoContentType": "image/png",
			  "photoObjectKey": "%s",
			  "photoSha256": "%s",
			  "photoSizeBytes": %d
			}
			""".formatted(clientSubmissionId, objectKey, sha256, sizeBytes);
	}

	private String sha256Hex(byte[] bytes) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
		} catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("SHA-256 algorithm is unavailable", exception);
		}
	}

	private record UploadedObject(String objectKey, String sha256) {
	}

	private record ReceiptReport(String reportId, String receiptToken) {
	}
}
