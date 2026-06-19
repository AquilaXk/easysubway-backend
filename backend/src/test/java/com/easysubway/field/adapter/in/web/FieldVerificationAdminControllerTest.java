package com.easysubway.field.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.easysubway.field.application.port.in.FieldVerificationUseCase;
import com.easysubway.field.application.port.in.UpdateFieldVerificationItemStatusCommand;
import com.easysubway.field.domain.FieldVerificationChangeHistory;
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
import org.springframework.http.MediaType;
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
	@DisplayName("관리자는 현장 검증 대상 목록을 조회한다")
	void adminListsStationFieldVerifications() throws Exception {
		mockMvc.perform(get("/admin/field-verifications/stations")
				.with(httpBasic("admin-user", "admin-test-password")))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.success").value(true))
			.andExpect(jsonPath("$.data[0].sessionId").value("field-verification-sangnoksu-2026-06"))
			.andExpect(jsonPath("$.data[0].stationId").value("station-sangnoksu"))
			.andExpect(jsonPath("$.data[0].status").value("IN_PROGRESS"))
			.andExpect(jsonPath("$.data[1].sessionId").value("field-verification-sadang-2026-06"))
			.andExpect(jsonPath("$.data[1].stationId").value("station-sadang"))
			.andExpect(jsonPath("$.data[1].status").value("PLANNED"));
	}

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
	@DisplayName("관리자는 사당역 현장 검증 세션과 항목을 조회한다")
	void adminGetsSadangFieldVerification() throws Exception {
		mockMvc.perform(get("/admin/field-verifications/stations/station-sadang")
				.with(httpBasic("admin-user", "admin-test-password")))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.success").value(true))
			.andExpect(jsonPath("$.data.sessionId").value("field-verification-sadang-2026-06"))
			.andExpect(jsonPath("$.data.stationId").value("station-sadang"))
			.andExpect(jsonPath("$.data.stationName").value("사당역"))
			.andExpect(jsonPath("$.data.status").value("PLANNED"))
			.andExpect(jsonPath("$.data.note").value("주요 환승역 현장 검증 확대 기준선"))
			.andExpect(jsonPath("$.data.items[0].targetName").value("2호선/4호선 출구 연결"))
			.andExpect(jsonPath("$.data.items[4].targetName").value("2호선과 4호선 환승 접근 동선"));
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
	@DisplayName("관리자는 사당역 현장 검증 결과를 CSV로 내려받는다")
	void adminDownloadsSadangFieldVerificationCsv() throws Exception {
		mockMvc.perform(get("/admin/field-verifications/stations/station-sadang/export.csv")
				.with(httpBasic("admin-user", "admin-test-password")))
			.andExpect(status().isOk())
			.andExpect(header().string(
				HttpHeaders.CONTENT_DISPOSITION,
				"attachment; filename=\"easysubway-field-verification-station-sadang.csv\""
			))
			.andExpect(header().string(HttpHeaders.CONTENT_TYPE, "text/csv;charset=UTF-8"))
			.andExpect(result -> {
				String csv = result.getResponse().getContentAsString();
				assertThat(csv)
					.startsWith("sessionId,stationId,stationName,verifiedAt,verifiedBy,sessionStatus,itemType,itemLabel,targetName,itemStatus,note\n")
					.contains("field-verification-sadang-2026-06,station-sadang,사당역,")
					.contains("EXIT,출구,2호선/4호선 출구 연결,PLANNED,")
					.contains("PLATFORM_TRANSFER,승강장/환승 동선,2호선과 4호선 환승 접근 동선,PLANNED,");
			});
	}

	@Test
	@DisplayName("현장 검증 CSV는 파일명과 CSV 값을 안전하게 escape한다")
	void fieldVerificationCsvEscapesFilenameAndCsvValues() {
		FieldVerificationAdminController controller = new FieldVerificationAdminController(new FieldVerificationUseCase() {
			@Override
			public List<FieldVerificationSession> listStationVerifications() {
				return List.of();
			}

			@Override
			public FieldVerificationSession updateItemStatus(UpdateFieldVerificationItemStatusCommand command) {
				throw new UnsupportedOperationException("not used in csv escaping test");
			}

			@Override
			public List<FieldVerificationChangeHistory> listStationChangeHistory(String stationId) {
				return List.of();
			}

			@Override
			public FieldVerificationSession getStationVerification(String stationId) {
				return new FieldVerificationSession(
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
				);
			}
		});

		var response = controller.stationFieldVerificationCsv("station\r\nid");

		assertThat(response.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION))
			.isEqualTo("attachment; filename=\"easysubway-field-verification-station__id.csv\"");
		assertThat(response.getBody())
			.contains("\"station\r\nid\",\"상록수,검증\",2026-06-19,\"field\"\"team\",IN_PROGRESS,EXIT,출구,'=cmd,VERIFIED,\"쉼표, 따옴표\"\" 개행\n수식 +1\"");
	}

	@Test
	@DisplayName("현장 검증 API는 관리자 인증을 요구한다")
	void fieldVerificationRequiresAdminAuthentication() throws Exception {
		mockMvc.perform(get("/admin/field-verifications/stations"))
			.andExpect(status().isUnauthorized());

		mockMvc.perform(get("/admin/field-verifications/stations")
				.with(httpBasic("anonymous-user-1", "user-test-password")))
			.andExpect(status().isForbidden());

		mockMvc.perform(get("/admin/field-verifications/stations/station-sangnoksu"))
			.andExpect(status().isUnauthorized());

		mockMvc.perform(get("/admin/field-verifications/stations/station-sangnoksu")
				.with(httpBasic("anonymous-user-1", "user-test-password")))
			.andExpect(status().isForbidden());
	}

	@Test
	@DisplayName("관리자는 현장 검증 항목 상태와 비고를 변경한다")
	void adminUpdatesFieldVerificationItemStatus() throws Exception {
		mockMvc.perform(patch("/admin/field-verifications/stations/station-sadang/items/field-verification-sadang-elevator/status")
				.with(httpBasic("admin-user", "admin-test-password"))
				.with(csrf())
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "status": "NEEDS_RECHECK",
					  "note": "엘리베이터 운행 중지 안내문 확인 필요"
					}
					"""))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.success").value(true))
			.andExpect(jsonPath("$.data.stationId").value("station-sadang"))
			.andExpect(jsonPath("$.data.status").value("NEEDS_RECHECK"))
			.andExpect(jsonPath("$.data.items[1].itemId").value("field-verification-sadang-elevator"))
			.andExpect(jsonPath("$.data.items[1].status").value("NEEDS_RECHECK"))
			.andExpect(jsonPath("$.data.items[1].note").value("엘리베이터 운행 중지 안내문 확인 필요"));
	}

	@Test
	@DisplayName("관리자는 역별 현장 검증 변경 이력을 조회한다")
	void adminListsStationFieldVerificationChangeHistory() throws Exception {
		mockMvc.perform(patch("/admin/field-verifications/stations/station-sadang/items/field-verification-sadang-elevator/status")
				.with(httpBasic("admin-user", "admin-test-password"))
				.with(csrf())
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "status": "NEEDS_RECHECK",
					  "note": "엘리베이터 운행 중지 안내문 확인 필요"
					}
					"""))
			.andExpect(status().isOk());

		mockMvc.perform(get("/admin/field-verifications/stations/station-sadang/history")
				.with(httpBasic("admin-user", "admin-test-password")))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.success").value(true))
			.andExpect(jsonPath("$.data[0].sessionId").value("field-verification-sadang-2026-06"))
			.andExpect(jsonPath("$.data[0].stationId").value("station-sadang"))
			.andExpect(jsonPath("$.data[0].itemId").value("field-verification-sadang-elevator"))
			.andExpect(jsonPath("$.data[0].previousStatus").value("PLANNED"))
			.andExpect(jsonPath("$.data[0].newStatus").value("NEEDS_RECHECK"))
			.andExpect(jsonPath("$.data[0].previousNote").doesNotExist())
			.andExpect(jsonPath("$.data[0].newNote").value("엘리베이터 운행 중지 안내문 확인 필요"))
			.andExpect(jsonPath("$.data[0].changedBy").value("admin-user"))
			.andExpect(jsonPath("$.data[0].changedAt").isNotEmpty());
	}

	@Test
	@DisplayName("존재하지 않는 역의 현장 검증 변경 이력 조회는 공통 404 응답을 반환한다")
	void listMissingStationFieldVerificationChangeHistoryReturnsCommonError() throws Exception {
		mockMvc.perform(get("/admin/field-verifications/stations/missing-station/history")
				.with(httpBasic("admin-user", "admin-test-password")))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.success").value(false))
			.andExpect(jsonPath("$.data").doesNotExist())
			.andExpect(jsonPath("$.message").value("현장 검증 기준선을 찾을 수 없습니다."));
	}

	@Test
	@DisplayName("현장 검증 항목 상태 변경 API는 관리자 인증을 요구한다")
	void updateFieldVerificationItemStatusRequiresAdminAuthentication() throws Exception {
		mockMvc.perform(patch("/admin/field-verifications/stations/station-sadang/items/field-verification-sadang-elevator/status")
				.with(csrf())
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "status": "VERIFIED"
					}
					"""))
			.andExpect(status().isUnauthorized());

		mockMvc.perform(patch("/admin/field-verifications/stations/station-sadang/items/field-verification-sadang-elevator/status")
				.with(httpBasic("anonymous-user-1", "user-test-password"))
				.with(csrf())
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "status": "VERIFIED"
					}
					"""))
			.andExpect(status().isForbidden());
	}

	@Test
	@DisplayName("존재하지 않는 현장 검증 항목 상태 변경은 공통 404 응답을 반환한다")
	void updateMissingFieldVerificationItemStatusReturnsCommonError() throws Exception {
		mockMvc.perform(patch("/admin/field-verifications/stations/station-sadang/items/missing-item/status")
				.with(httpBasic("admin-user", "admin-test-password"))
				.with(csrf())
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "status": "VERIFIED"
					}
					"""))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.success").value(false))
			.andExpect(jsonPath("$.data").doesNotExist())
			.andExpect(jsonPath("$.message").value("현장 검증 항목을 찾을 수 없습니다."));
	}

	@Test
	@DisplayName("현장 검증 항목 상태 변경 요청은 상태값을 요구한다")
	void updateFieldVerificationItemStatusRequiresStatus() throws Exception {
		mockMvc.perform(patch("/admin/field-verifications/stations/station-sadang/items/field-verification-sadang-elevator/status")
				.with(httpBasic("admin-user", "admin-test-password"))
				.with(csrf())
				.contentType(MediaType.APPLICATION_JSON)
				.content("{}"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.success").value(false))
			.andExpect(jsonPath("$.data").doesNotExist())
			.andExpect(jsonPath("$.message").value("현장 검증 상태를 선택해야 합니다."));
	}

	@Test
	@DisplayName("현장 검증 항목 상태 변경 요청은 완료 또는 재확인 필요 상태만 허용한다")
	void updateFieldVerificationItemStatusRejectsWorkflowStatus() throws Exception {
		mockMvc.perform(patch("/admin/field-verifications/stations/station-sadang/items/field-verification-sadang-elevator/status")
				.with(httpBasic("admin-user", "admin-test-password"))
				.with(csrf())
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "status": "PLANNED"
					}
					"""))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.success").value(false))
			.andExpect(jsonPath("$.data").doesNotExist())
			.andExpect(jsonPath("$.message").value("현장 검증 상태는 VERIFIED 또는 NEEDS_RECHECK만 허용됩니다."));
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

	@Test
	@DisplayName("현장 검증 변경 이력 API는 관리자 인증을 요구한다")
	void fieldVerificationChangeHistoryRequiresAdminAuthentication() throws Exception {
		mockMvc.perform(get("/admin/field-verifications/stations/station-sadang/history"))
			.andExpect(status().isUnauthorized());

		mockMvc.perform(get("/admin/field-verifications/stations/station-sadang/history")
				.with(httpBasic("anonymous-user-1", "user-test-password")))
			.andExpect(status().isForbidden());
	}
}
