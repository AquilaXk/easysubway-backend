package com.easysubway.report.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jayway.jsonpath.JsonPath;
import java.nio.file.Files;
import java.nio.file.Path;
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
@DisplayName("시설 신고 API golden fixture")
class FacilityReportApiGoldenFixtureTest {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	@Test
	@DisplayName("신고 생성·조회·확인 응답은 golden fixture와 일치한다")
	void reportResponsesMatchGoldenFixtures() throws Exception {
		String createBody = mockMvc.perform(post("/api/v1/reports")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "clientSubmissionId": "golden-report-create-1",
					  "stationId": "station-sangnoksu",
					  "facilityId": "facility-sangnoksu-elevator-1",
					  "reportType": "BROKEN",
					  "description": "엘리베이터 문이 열리지 않습니다."
					}
					"""))
			.andExpect(status().isCreated())
			.andReturn().getResponse().getContentAsString();
		assertFixture("report-create.created.json", normalizeReport(createBody, true));

		String reportId = JsonPath.read(createBody, "$.data.id");
		String receiptToken = JsonPath.read(createBody, "$.data.receiptToken");
		String statusBody = mockMvc.perform(get("/api/v1/reports/{reportId}", reportId)
				.header("X-Easysubway-Report-Receipt-Token", receiptToken))
			.andExpect(status().isOk())
			.andReturn().getResponse().getContentAsString();
		assertFixture("report-status.ok.json", normalizeReport(statusBody, false));

		String confirmedReportId = createReportForConfirmation();
		String confirmBody = mockMvc.perform(post("/api/v1/reports/{reportId}/confirm", confirmedReportId)
				.with(httpBasic("basic-user", "user-test-password"))
				.with(csrf()))
			.andExpect(status().isOk())
			.andReturn().getResponse().getContentAsString();
		assertFixture("report-confirm.ok.json", normalizeReport(confirmBody, false));
	}

	@Test
	@DisplayName("신고 사진 업로드 intent 응답은 golden fixture와 일치한다")
	void uploadIntentMatchesGoldenFixture() throws Exception {
		String body = mockMvc.perform(post("/api/v1/report-uploads")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "clientSubmissionId": "golden-upload-intent-1",
					  "photoFileName": "elevator.jpg",
					  "photoContentType": "image/jpeg",
					  "photoSha256": "2c8648d103e3dd7ad87660da0f126a1443b6d21ac1bd3ec000c5e24e2373a90c",
					  "photoSizeBytes": 11
					}
					"""))
			.andExpect(status().isCreated())
			.andReturn().getResponse().getContentAsString();

		ObjectNode root = (ObjectNode) objectMapper.readTree(body);
		ObjectNode data = (ObjectNode) root.get("data");
		assertHasNonNull(data, "objectKey", "uploadUrl", "expiresAt");
		data.put("objectKey", "reports/__REPORT_UPLOAD_OBJECT_KEY__.jpg");
		data.put("uploadUrl", "/api/v1/report-uploads/__REPORT_UPLOAD_ID__");
		data.put("expiresAt", "__EXPIRES_AT__");
		assertFixture("report-upload-intent.created.json", root);
	}

	private String createReportForConfirmation() throws Exception {
		String body = mockMvc.perform(post("/api/v1/reports")
				.with(httpBasic("basic-user", "user-test-password"))
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "stationId": "station-sangnoksu",
					  "facilityId": "facility-sangnoksu-elevator-1",
					  "reportType": "BROKEN",
					  "description": "처리 결과를 확인할 신고"
					}
					"""))
			.andExpect(status().isCreated())
			.andReturn().getResponse().getContentAsString();
		String reportId = JsonPath.read(body, "$.data.id");

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
		return reportId;
	}

	private JsonNode normalizeReport(String body, boolean includeReceiptToken) throws Exception {
		ObjectNode root = (ObjectNode) objectMapper.readTree(body);
		ObjectNode data = (ObjectNode) root.get("data");
		assertHasNonNull(data, "id", "createdAt");
		if (includeReceiptToken) {
			assertHasNonNull(data, "receiptToken");
		}
		data.put("id", "__REPORT_ID__");
		data.put("createdAt", "__CREATED_AT__");
		if (data.has("reviewedAt") && !data.get("reviewedAt").isNull()) {
			data.put("reviewedAt", "__REVIEWED_AT__");
		}
		if (data.has("receiptToken") || includeReceiptToken) {
			data.put("receiptToken", "__RECEIPT_TOKEN__");
		}
		if (data.has("publicReceiptCode")) {
			assertHasNonNull(data, "publicReceiptCode");
			data.put("publicReceiptCode", "__PUBLIC_RECEIPT_CODE__");
		}
		return root;
	}

	private void assertHasNonNull(ObjectNode data, String... fields) {
		for (String field : fields) {
			assertThat(data.hasNonNull(field)).as(field).isTrue();
		}
	}

	private void assertFixture(String fileName, JsonNode actual) throws Exception {
		JsonNode expected = objectMapper.readTree(
			Files.readString(repositoryRoot().resolve("contracts/api/fixtures").resolve(fileName))
		);
		assertThat(actual).isEqualTo(expected);
	}

	private Path repositoryRoot() {
		Path path = Path.of(System.getProperty("user.dir")).toAbsolutePath();
		while (path != null && !Files.exists(path.resolve("contracts/api/fixtures"))) {
			path = path.getParent();
		}
		return path;
	}
}
