package com.easysubway.field.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.easysubway.field.domain.FieldVerificationItem;
import com.easysubway.field.domain.FieldVerificationItemType;
import com.easysubway.field.domain.FieldVerificationSession;
import com.easysubway.field.domain.FieldVerificationStatus;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {
	"easysubway.admin.username=admin-user",
	"easysubway.admin.password=admin-test-password",
	"easysubway.user.username=anonymous-user-1",
	"easysubway.user.password=user-test-password"
})
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
@DisplayName("관리자 현장 검증 API")
class FieldVerificationAdminControllerTest {

	@Autowired
	private MockMvc mockMvc;

	@Test
	@DisplayName("관리자는 역별 현장 검증 세션과 항목을 조회한다")
	void adminGetsStationFieldVerification() throws Exception {
		mockMvc.perform(get("/admin/field-verifications/stations/station-sangnoksu")
				.with(httpBasic("admin-user", "admin-test-password")))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.success").value(true))
			.andExpect(jsonPath("$.data.sessionId").value("field-verification-sangnoksu-2026-06"))
			.andExpect(jsonPath("$.data.stationId").value("station-sangnoksu"))
			.andExpect(jsonPath("$.data.stationName").value("상록수역"))
			.andExpect(jsonPath("$.data.status").value("IN_PROGRESS"))
			.andExpect(jsonPath("$.data.items[0].type").value("EXIT"))
			.andExpect(jsonPath("$.data.items[0].label").value("출구"))
			.andExpect(jsonPath("$.data.items[4].type").value("PLATFORM_TRANSFER"))
			.andExpect(jsonPath("$.data.items[4].label").value("승강장/환승 동선"));
	}

	@Test
	@DisplayName("관리자는 역별 현장 검증 결과를 CSV로 내려받는다")
	void adminDownloadsStationFieldVerificationCsv() throws Exception {
		mockMvc.perform(get("/admin/field-verifications/stations/station-sangnoksu/export.csv")
				.with(httpBasic("admin-user", "admin-test-password")))
			.andExpect(status().isOk())
			.andExpect(header().string(
				HttpHeaders.CONTENT_DISPOSITION,
				"attachment; filename=\"easysubway-field-verification-station-sangnoksu.csv\""
			))
			.andExpect(header().string(HttpHeaders.CONTENT_TYPE, "text/csv;charset=UTF-8"))
			.andExpect(result -> {
				String csv = result.getResponse().getContentAsString();
				assertThat(csv)
					.startsWith("sessionId,stationId,stationName,verifiedAt,verifiedBy,sessionStatus,itemType,itemLabel,targetName,itemStatus,note\n")
					.contains("field-verification-sangnoksu-2026-06,station-sangnoksu,상록수역,")
					.contains("EXIT,출구,주요 출구 연결,VERIFIED,")
					.contains("PLATFORM_TRANSFER,승강장/환승 동선,승강장과 환승 접근 동선,PLANNED,");
			});
	}

	@Test
	@DisplayName("현장 검증 CSV는 파일명과 CSV 값을 안전하게 escape한다")
	void fieldVerificationCsvEscapesFilenameAndCsvValues() {
		FieldVerificationAdminController controller = new FieldVerificationAdminController(stationId -> new FieldVerificationSession(
			"session-formula",
			"station\r\nid",
			"상록수,검증",
			LocalDate.of(2026, 6, 19),
			"field\"team",
			FieldVerificationStatus.IN_PROGRESS,
			"세션 비고",
			List.of(new FieldVerificationItem(
				"item-1",
				FieldVerificationItemType.EXIT,
				"=cmd",
				FieldVerificationStatus.VERIFIED,
				"쉼표, 따옴표\" 개행\n수식 +1"
			))
		));

		var response = controller.stationFieldVerificationCsv("station\r\nid");

		assertThat(response.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION))
			.isEqualTo("attachment; filename=\"easysubway-field-verification-station__id.csv\"");
		assertThat(response.getBody())
			.contains("\"station\r\nid\",\"상록수,검증\",2026-06-19,\"field\"\"team\",IN_PROGRESS,EXIT,출구,'=cmd,VERIFIED,\"쉼표, 따옴표\"\" 개행\n수식 +1\"");
	}

	@Test
	@DisplayName("현장 검증 API는 관리자 인증을 요구한다")
	void fieldVerificationRequiresAdminAuthentication() throws Exception {
		mockMvc.perform(get("/admin/field-verifications/stations/station-sangnoksu"))
			.andExpect(status().isUnauthorized());

		mockMvc.perform(get("/admin/field-verifications/stations/station-sangnoksu")
				.with(httpBasic("anonymous-user-1", "user-test-password")))
			.andExpect(status().isForbidden());
	}

	@Test
	@DisplayName("현장 검증 CSV export는 관리자 인증을 요구한다")
	void fieldVerificationCsvRequiresAdminAuthentication() throws Exception {
		mockMvc.perform(get("/admin/field-verifications/stations/station-sangnoksu/export.csv"))
			.andExpect(status().isUnauthorized());

		mockMvc.perform(get("/admin/field-verifications/stations/station-sangnoksu/export.csv")
				.with(httpBasic("anonymous-user-1", "user-test-password")))
			.andExpect(status().isForbidden());
	}
}
