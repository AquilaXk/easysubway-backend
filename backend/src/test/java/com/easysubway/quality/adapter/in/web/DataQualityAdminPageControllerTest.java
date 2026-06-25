package com.easysubway.quality.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.easysubway.common.web.WebMessageResolver;
import com.easysubway.quality.application.port.in.DataQualityUseCase;
import com.easysubway.quality.domain.DataQualitySummary;
import com.easysubway.report.application.port.in.FacilityReportUseCase;
import com.easysubway.report.domain.RepeatedBrokenFacilityReportSummary;
import com.easysubway.transit.application.port.in.TransitMasterQueryUseCase;
import com.easysubway.transit.domain.AccessibilityFacility;
import com.easysubway.transit.domain.AccessibilityFacilityStatus;
import com.easysubway.transit.domain.AccessibilityFacilityType;
import com.easysubway.transit.domain.DataConfidenceLevel;
import com.easysubway.transit.domain.DataQualityLevel;
import com.easysubway.transit.domain.DataSourceType;
import com.easysubway.transit.domain.Station;
import com.easysubway.transit.domain.StationNotFoundException;
import com.easysubway.transit.domain.StationWithLines;
import com.jayway.jsonpath.JsonPath;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.ui.ExtendedModelMap;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {
	"easysubway.admin.username=admin-user",
	"easysubway.admin.password=admin-test-password",
	"easysubway.user.username=basic-user",
	"easysubway.user.password=user-test-password"
})
@AutoConfigureMockMvc
@DisplayName("관리자 데이터 품질 대시보드")
class DataQualityAdminPageControllerTest {

	@Autowired
	private MockMvc mockMvc;

	@Test
	@DisplayName("관리자는 데이터 품질 대시보드에서 주요 집계와 보강 대상을 확인한다")
	void adminGetsDataQualityDashboardPage() throws Exception {
		String acceptedReportId = createReport("검증률에 반영할 승인 신고");
		String pendingReportId = createReport("검증률에 반영할 대기 신고");
		acceptReport(acceptedReportId);

		String html = mockMvc.perform(get("/admin/data-quality/page")
				.with(httpBasic("admin-user", "admin-test-password")))
			.andExpect(status().isOk())
			.andReturn()
			.getResponse()
			.getContentAsString();

		assertThat(html)
			.contains("데이터 품질 대시보드")
			.contains("전체 역")
			.contains(">2<")
			.contains("전체 출구")
			.contains("전체 시설")
			.contains(">3<")
			.contains("확인 필요한 시설")
			.contains("갱신 지연 시설")
			.contains("검수일 없는 역")
			.contains("Level 1")
			.contains("기본 정보 확인")
			.contains("높음")
			.contains("보통")
			.contains("확인 필요")
			.contains("지역별 데이터 품질")
			.contains("수도권")
			.contains("운영기관")
			.contains("노선")
			.contains("역")
			.contains("Level 2")
			.contains("Level 3")
			.contains("Level 4")
			.contains("시설 상태 갱신 지연")
			.contains("상태")
			.contains("지연 시설")
			.contains("사용자 제보 검증률")
			.contains("전체 제보")
			.contains("검증 완료")
			.contains("검증 대기")
			.contains("50%")
			.contains("접수됨")
			.contains("반영됨")
			.contains("역별 접근성 점수")
			.contains("접근성 점수")
			.contains("보강 사유")
			.contains("접근성 개선 우선순위")
			.contains("우선순위 점수")
			.contains("개선 사유")
			.contains("장애인 화장실")
			.contains("확인 필요 상태")
			.contains("신뢰도 확인 필요")
			.contains("반복 고장 신고 시설")
			.contains("역")
			.contains("시설")
			.contains("현재 상태")
			.contains("고장 신고 수")
			.contains("상록수")
			.contains("1번 출구 엘리베이터")
			.contains("정상")
			.doesNotContain("station-sangnoksu")
			.doesNotContain("exit-sangnoksu")
			.doesNotContain("facility-sangnoksu")
			.doesNotContain(acceptedReportId)
			.doesNotContain(pendingReportId);
	}

	@Test
	@DisplayName("반복 고장 신고 시설 중 현재 master data와 맞지 않는 이력은 대시보드 행에서 제외한다")
	void dashboardSkipsStaleRepeatedBrokenReportTargets() {
		DataQualityUseCase dataQualityUseCase = mock(DataQualityUseCase.class);
		TransitMasterQueryUseCase transitMasterQueryUseCase = mock(TransitMasterQueryUseCase.class);
		FacilityReportUseCase facilityReportUseCase = mock(FacilityReportUseCase.class);
		DataQualityAdminPageController controller = new DataQualityAdminPageController(
			dataQualityUseCase,
			transitMasterQueryUseCase,
			facilityReportUseCase,
			WebMessageResolver.defaultMessages()
		);
		when(dataQualityUseCase.summarizeDataQuality()).thenReturn(emptySummary());
		when(transitMasterQueryUseCase.listRegions()).thenReturn(List.of());
		when(facilityReportUseCase.countReportsByStatus()).thenReturn(Map.of());
		when(facilityReportUseCase.listRepeatedBrokenReportFacilities()).thenReturn(List.of(
			new RepeatedBrokenFacilityReportSummary("station-sangnoksu", "facility-removed", 2),
			new RepeatedBrokenFacilityReportSummary("station-removed", "facility-old", 3),
			new RepeatedBrokenFacilityReportSummary("station-sangnoksu", "facility-sangnoksu-elevator-1", 4)
		));
		when(transitMasterQueryUseCase.getStation("station-sangnoksu")).thenReturn(station("station-sangnoksu", "상록수"));
		when(transitMasterQueryUseCase.getStation("station-removed")).thenThrow(new StationNotFoundException());
		when(transitMasterQueryUseCase.listStationFacilities("station-sangnoksu"))
			.thenReturn(List.of(facility("facility-sangnoksu-elevator-1", "1번 출구 엘리베이터")));

		ExtendedModelMap model = new ExtendedModelMap();
		String viewName = controller.dataQualityDashboardPage(model);

		assertThat(viewName).isEqualTo("admin/quality/dashboard");
		DataQualityAdminPageController.DataQualityDashboardView view =
			(DataQualityAdminPageController.DataQualityDashboardView) model.getAttribute("summary");
		assertThat(view.repeatedBrokenFacilityRows()).hasSize(1);
		DataQualityAdminPageController.RepeatedBrokenFacilityRow row = view.repeatedBrokenFacilityRows().getFirst();
		assertThat(row.stationName()).isEqualTo("상록수");
		assertThat(row.facilityName()).isEqualTo("1번 출구 엘리베이터");
		assertThat(row.statusLabel()).isEqualTo("정상");
		assertThat(row.reportCount()).isEqualTo(4);
	}

	@Test
	@DisplayName("데이터 품질 대시보드는 관리자 인증을 요구한다")
	void dataQualityDashboardRequiresAdminAuthentication() throws Exception {
		mockMvc.perform(get("/admin/data-quality/page"))
			.andExpect(status().isUnauthorized());

		mockMvc.perform(get("/admin/data-quality/page")
				.with(httpBasic("basic-user", "user-test-password")))
			.andExpect(status().isForbidden());
	}

	private String createReport(String description) throws Exception {
		String response = mockMvc.perform(post("/api/v1/reports")
				.with(httpBasic("basic-user", "user-test-password"))
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "stationId": "station-sangnoksu",
					  "facilityId": "facility-sangnoksu-elevator-1",
					  "reportType": "BROKEN",
					  "description": "%s"
					}
					""".formatted(description)))
			.andExpect(status().isCreated())
			.andReturn()
			.getResponse()
			.getContentAsString();
		return JsonPath.read(response, "$.data.id");
	}

	private void acceptReport(String reportId) throws Exception {
		mockMvc.perform(post("/admin/reports/{reportId}/page/review", reportId)
				.with(httpBasic("admin-user", "admin-test-password"))
				.with(csrf())
				.contentType(MediaType.APPLICATION_FORM_URLENCODED)
				.param("decision", "ACCEPT"))
			.andExpect(status().is3xxRedirection());
	}

	private static DataQualitySummary emptySummary() {
		return new DataQualitySummary(
			0,
			0,
			0,
			Map.of(),
			List.of(),
			Map.of(),
			Map.of(),
			0,
			0,
			Map.of(),
			0,
			List.of(),
			List.of()
		);
	}

	private static StationWithLines station(String id, String nameKo) {
		return new StationWithLines(
			new Station(
				id,
				nameKo,
				"Sangnoksu",
				"수도권",
				BigDecimal.valueOf(37.302),
				BigDecimal.valueOf(126.866),
				DataQualityLevel.LEVEL_1,
				DataSourceType.ADMIN_VERIFIED,
				LocalDate.of(2026, 1, 1),
				true
			),
			List.of()
		);
	}

	private static AccessibilityFacility facility(String id, String name) {
		return new AccessibilityFacility(
			id,
			"station-sangnoksu",
			"exit-sangnoksu-1",
			AccessibilityFacilityType.ELEVATOR,
			name,
			"B1",
			"1F",
			BigDecimal.valueOf(37.302),
			BigDecimal.valueOf(126.866),
			"승강장 연결",
			AccessibilityFacilityStatus.NORMAL,
			DataConfidenceLevel.HIGH,
			DataSourceType.ADMIN_VERIFIED,
			LocalDate.of(2026, 1, 1)
		);
	}
}
