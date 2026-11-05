package com.easysubway.transit.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.forwardedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.servlet.http.HttpSession;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

@SpringBootTest(properties = {
	"easysubway.admin.username=admin-user",
	"easysubway.admin.password=admin-test-password",
	"easysubway.user.username=basic-user",
	"easysubway.user.password=user-test-password"
})
@AutoConfigureMockMvc
@DisplayName("관리자 시설 상태 화면")
class TransitFacilityAdminPageControllerTest {

	@Autowired
	private MockMvc mockMvc;

	@Test
	@DisplayName("관리자는 시설 상태 화면에서 시설과 현재 상태를 확인한다")
	void adminGetsFacilityStatusPage() throws Exception {
		String html = mockMvc.perform(get("/admin/facilities/page")
				.with(httpBasic("admin-user", "admin-test-password")))
			.andExpect(status().isOk())
			.andReturn()
			.getResponse()
			.getContentAsString();

		assertThat(html)
			.contains("시설 상태 관리")
			.contains("상록수")
			.contains("1번 출구 엘리베이터")
			.contains("엘리베이터")
			.contains("정상")
			.contains("장애인 화장실")
			.contains("확인 필요")
			.contains("최근 확인된 정보")
			.contains("name=\"status\"")
			.contains("name=\"_csrf\"")
			// #2313 PR②: 상태는 상태 텍스트(.admin-status, ● 점 + 텍스트)로, 갱신일은 상대 시간 + 정확한 날짜
			// 병기로 렌더한다.
			.contains("class=\"admin-status")
			.contains("class=\"data-verified-relative\"");
	}

	@Test
	@DisplayName("시설 상태 화면은 page size와 현재 페이지를 링크에 표시한다")
	void facilityStatusPageShowsPaginationLinks() throws Exception {
		String html = mockMvc.perform(get("/admin/facilities/page")
				.param("size", "1")
				.with(httpBasic("admin-user", "admin-test-password")))
			.andExpect(status().isOk())
			.andReturn()
			.getResponse()
			.getContentAsString();

		assertThat(html)
			.contains("시설 상태 목록 페이지")
			.contains("aria-current=\"page\"")
			.contains("page=1&amp;size=1")
			.contains("다음");
	}

	@Test
	@DisplayName("관리자는 시설 상태 화면에서 상태를 변경한 뒤 목록으로 돌아온다")
	@DirtiesContext(methodMode = DirtiesContext.MethodMode.AFTER_METHOD)
	void adminUpdatesFacilityStatusFromPageAndRedirectsToList() throws Exception {
		mockMvc.perform(post("/admin/facilities/facility-sangnoksu-elevator-1/page/status")
				.with(httpBasic("admin-user", "admin-test-password"))
				.with(csrf())
				.with(commandToken("/admin/facilities/page"))
				.contentType(MediaType.APPLICATION_FORM_URLENCODED)
				.param("status", "BROKEN"))
			.andExpect(status().is3xxRedirection())
			.andExpect(header().string("Location", "/admin/facilities/page"));

		String html = mockMvc.perform(get("/admin/facilities/page")
				.with(httpBasic("admin-user", "admin-test-password")))
			.andExpect(status().isOk())
			.andReturn()
			.getResponse()
			.getContentAsString();

		assertThat(html)
			.contains("1번 출구 엘리베이터")
			.contains("고장")
			.contains("관리자 확인");
	}

	@Test
	@DisplayName("시설 상태 변경 validation 실패는 관리자 shell 안에서 field error를 보여준다")
	void facilityStatusValidationErrorRendersAdminHtml() throws Exception {
		String html = mockMvc.perform(post("/admin/facilities/facility-sangnoksu-elevator-1/page/status")
				.with(httpBasic("admin-user", "admin-test-password"))
				.with(csrf())
				.with(commandToken("/admin/facilities/page"))
				.contentType(MediaType.APPLICATION_FORM_URLENCODED))
			.andExpect(status().isBadRequest())
			.andReturn()
			.getResponse()
			.getContentAsString();

		assertThat(html)
			.contains("통합 관리자")
			.contains("입력값을 확인해 주세요")
			.contains("시설 상태를 선택해야 합니다.")
			.contains("1번 출구 엘리베이터");
	}

	@Test
	@DisplayName("시설 편집 validation 실패는 입력값과 선택 상태를 보존한다")
	void facilityEditorValidationErrorPreservesInput() throws Exception {
		String html = mockMvc.perform(post("/admin/facilities/editor/page")
				.with(httpBasic("admin-user", "admin-test-password"))
				.with(csrf())
				.with(commandToken("/admin/facilities/page"))
				.contentType(MediaType.APPLICATION_FORM_URLENCODED)
				.param("stationId", "station-sangnoksu")
				.param("type", "ELEVATOR")
				.param("floorFrom", "B1")
				.param("floorTo", "1F")
				.param("status", "BROKEN")
				.param("dataConfidence", "HIGH")
				.param("dataSourceType", "OFFICIAL_API")
				.param("description", "입력값 보존 메모"))
			.andExpect(status().isBadRequest())
			.andReturn()
			.getResponse()
			.getContentAsString();

		assertThat(html)
			.contains("시설명을 입력해야 합니다.")
			.contains("value=\"B1\"")
			.contains("value=\"1F\"")
			.contains("입력값 보존 메모");
	}

	@Test
	@DisplayName("시설 편집 숫자 변환 실패는 위도 원본 입력값을 보존한다")
	void facilityEditorTypeMismatchPreservesRejectedLatitude() throws Exception {
		String html = mockMvc.perform(post("/admin/facilities/editor/page")
				.with(httpBasic("admin-user", "admin-test-password"))
				.with(csrf())
				.with(commandToken("/admin/facilities/page"))
				.contentType(MediaType.APPLICATION_FORM_URLENCODED)
				.param("stationId", "station-sangnoksu")
				.param("type", "ELEVATOR")
				.param("name", "1번 출구 엘리베이터")
				.param("floorFrom", "B1")
				.param("floorTo", "1F")
				.param("latitude", "not-a-number")
				.param("longitude", "126.866768")
				.param("status", "BROKEN")
				.param("dataConfidence", "HIGH")
				.param("dataSourceType", "OFFICIAL_API")
				.param("description", "숫자 변환 실패 메모"))
			.andExpect(status().isBadRequest())
			.andReturn()
			.getResponse()
			.getContentAsString();

		assertThat(html)
			.contains("입력값을 확인해 주세요")
			.contains("value=\"not-a-number\"")
			.contains("value=\"126.866768\"")
			.contains("숫자 변환 실패 메모");
	}

	@Test
	@DisplayName("관리자 시설 상태 화면은 관리자 인증을 요구한다")
	void facilityStatusPagesRequireAdminAuthentication() throws Exception {
		mockMvc.perform(get("/admin/facilities/page"))
			.andExpect(status().isUnauthorized());

		mockMvc.perform(get("/admin/facilities/page")
				.with(httpBasic("basic-user", "user-test-password")))
			.andExpect(status().isForbidden());

		mockMvc.perform(post("/admin/facilities/facility-sangnoksu-elevator-1/page/status")
				.with(csrf())
				.contentType(MediaType.APPLICATION_FORM_URLENCODED)
				.param("status", "BROKEN"))
			.andExpect(status().isUnauthorized());

		mockMvc.perform(post("/admin/facilities/facility-sangnoksu-elevator-1/page/status")
				.with(httpBasic("basic-user", "user-test-password"))
				.with(csrf())
				.contentType(MediaType.APPLICATION_FORM_URLENCODED)
				.param("status", "BROKEN"))
			.andExpect(status().isForbidden())
			.andExpect(forwardedUrl("/admin/error/page"));
	}

	@Test
	@DisplayName("시설·역 목록의 경과일 기준(today)과 역 상세 제보·현장 확인 시각은 한국 표준시(KST)를 따른다")
	void facilityAndStationControllersUseKoreanStandardTimeTodayAndTimestamps() {
		java.time.Clock koreanEarlyMorningClock = java.time.Clock.fixed(
			java.time.Instant.parse("2026-06-12T16:00:00Z"),
			java.time.ZoneOffset.UTC
		);
		var repository = new com.easysubway.transit.adapter.out.persistence.UnavailableTransitMasterRepository();
		var transitMasterService = new com.easysubway.transit.application.service.TransitMasterService(
			repository,
			repository,
			koreanEarlyMorningClock
		);
		var reportUseCase = org.mockito.Mockito.mock(
			com.easysubway.report.application.port.in.FacilityReportUseCase.class
		);
		org.mockito.Mockito.when(reportUseCase.countPendingReportsByStation())
			.thenReturn(java.util.Map.of());

		var assembler = new TransitFacilityStatusAssembler(transitMasterService);
		var messages = com.easysubway.common.web.WebMessageResolver.defaultMessages();

		var facilityController = new TransitFacilityAdminPageController(
			assembler,
			transitMasterService,
			koreanEarlyMorningClock
		);
		var facilityModel = new org.springframework.ui.ConcurrentModel();
		facilityController.facilitiesPage(null, null, null, null, null, 0, 20, facilityModel);
		assertThat(facilityModel.getAttribute("today")).isEqualTo(java.time.LocalDate.of(2026, 6, 13));

		var stationController = new TransitStationAdminPageController(
			transitMasterService,
			transitMasterService,
			null,
			reportUseCase,
			null,
			com.easysubway.admin.web.AdminMasterLabelResolver.empty(),
			messages,
			koreanEarlyMorningClock
		);
		var stationModel = new org.springframework.ui.ConcurrentModel();
		stationController.stationsPage(null, null, null, null, 0, 20, stationModel);
		assertThat(stationModel.getAttribute("today")).isEqualTo(java.time.LocalDate.of(2026, 6, 13));

		var utcTime = java.time.LocalDateTime.of(2026, 6, 27, 15, 30);
		var reportSummary = new com.easysubway.report.domain.FacilityReportSummary(
			"report-station-kst",
			"R-KST",
			"basic-user",
			"station-sangnoksu",
			"facility-sangnoksu-elevator-1",
			com.easysubway.report.domain.FacilityReportType.BROKEN,
			"고장",
			false,
			null,
			null,
			null,
			com.easysubway.report.domain.FacilityReportStatus.SUBMITTED,
			utcTime,
			null,
			null
		);
		var stationReportRow = TransitStationAdminPageController.StationReportRow.from(
			reportSummary,
			messages,
			java.util.Map.of("facility-sangnoksu-elevator-1", "1번 출구 엘리베이터")
		);
		assertThat(stationReportRow.createdAt()).isEqualTo(java.time.LocalDateTime.of(2026, 6, 28, 0, 30));

		var fieldHistory = new com.easysubway.field.domain.FieldVerificationChangeHistory(
			"history-kst",
			"session-1",
			"station-sangnoksu",
			"item-1",
			com.easysubway.field.domain.FieldVerificationStatus.PLANNED,
			com.easysubway.field.domain.FieldVerificationStatus.VERIFIED,
			"operator",
			"note",
			"reason",
			utcTime
		);
		var historyRow = TransitStationAdminPageController.StationFieldHistoryRow.from(fieldHistory);
		assertThat(historyRow.changedAt()).isEqualTo(java.time.LocalDateTime.of(2026, 6, 28, 0, 30));
	}

	private String getAdminHtml(String path, MockHttpSession session) throws Exception {
		return mockMvc.perform(get(path)
				.session(session)
				.with(httpBasic("admin-user", "admin-test-password")))
			.andExpect(status().isOk())
			.andReturn()
			.getResponse()
			.getContentAsString();
	}

	private static String commandTokenFrom(String html) {
		Matcher matcher = Pattern.compile("name=\"commandToken\" value=\"([^\"]+)\"").matcher(html);
		assertThat(matcher.find()).isTrue();
		return matcher.group(1);
	}

	private RequestPostProcessor commandToken(String pagePath) {
		return request -> {
			MockHttpSession session = sessionFrom(request);
			try {
				request.setSession(session);
				request.addParameter("commandToken", commandTokenFrom(getAdminHtml(pagePath, session)));
				return request;
			} catch (Exception exception) {
				throw new AssertionError(exception);
			}
		};
	}

	private static MockHttpSession sessionFrom(MockHttpServletRequest request) {
		HttpSession session = request.getSession(false);
		if (session instanceof MockHttpSession mockHttpSession) {
			return mockHttpSession;
		}
		return new MockHttpSession();
	}
}
