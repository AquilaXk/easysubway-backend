package com.easysubway.operator.adapter.in.web;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;

import java.io.ByteArrayInputStream;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.http.HttpHeaders;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {
	"easysubway.admin.username=admin-user",
	"easysubway.admin.password=admin-test-password",
	"easysubway.operator.username=operator-user",
	"easysubway.operator.password=operator-test-password",
	"easysubway.user.username=basic-user",
	"easysubway.user.password=user-test-password"
})
@AutoConfigureMockMvc
@DisplayName("운영기관 접근성 리포트 API")
class OperatorAccessibilityReportControllerTest {

	@Autowired
	private MockMvc mockMvc;

	@TestConfiguration
	static class FixedClockConfiguration {

		@Bean
		Clock operatorAccessibilityReportTestClock() {
			return Clock.fixed(Instant.parse("2026-06-17T00:00:00Z"), ZoneId.of("Asia/Seoul"));
		}
	}

	@Test
	@DisplayName("운영기관 계정은 접근성 시설 현황 리포트를 JSON으로 조회한다")
	void operatorGetsAccessibilityReport() throws Exception {
		mockMvc.perform(get("/operator/api/accessibility-report")
				.with(httpBasic("operator-user", "operator-test-password")))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.success").value(true))
			// #2095: InMemoryTransitMasterRepository에 ITX-청춘 pilot 정차역 14곳이
			// 추가돼 역 수 집계가 2에서 16으로 늘었다.
			.andExpect(jsonPath("$.data.totalStations").value(16))
			.andExpect(jsonPath("$.data.totalFacilities").value(3))
			.andExpect(jsonPath("$.data.needsVerificationFacilityCount").value(1))
			.andExpect(jsonPath("$.data.stationQualityRows[0].description").value("일부 정보는 확인 중이에요"))
			.andExpect(jsonPath("$.data.stationQualityRows[1].description").value("시설 정보를 함께 볼 수 있어요"))
			.andExpect(jsonPath("$.data.stationQualityRows[2].description").value("쉬운 길 안내를 볼 수 있어요"))
			.andExpect(jsonPath("$.data.stationQualityRows[3].description").value("고장·공사 소식이 반영됐어요"))
			// #2095: region 목록이 이름순 정렬이라 강원권(ITX-청춘 pilot 3역)이 수도권보다
			// 앞에 온다. ITX-청춘 노선을 LINES에 추가했지만 그 운영기관인 한국철도공사는
			// 이미 수인분당선으로 "수도권" TransitOperator에 등록돼 있어(단일 region
			// 스키마상 강원권 전용 운영기관을 지어내지 않았으므로) 강원권 operatorCount는
			// 여전히 0이다.
			.andExpect(jsonPath("$.data.regionQualityRows[0].name").value("강원권"))
			.andExpect(jsonPath("$.data.regionQualityRows[0].operatorCount").value(0))
			.andExpect(jsonPath("$.data.regionQualityRows[1].name").value("수도권"))
			.andExpect(jsonPath("$.data.regionQualityRows[1].operatorCount").value(2))
			// station 접근성 점수 정렬(DataQualityService: score → region → stationName →
			// stationId)도 ITX-청춘 pilot 역 14곳(모두 접근성 시설 미등록이라 점수 0)이
			// 상록수(15점)보다 앞으로 온다. 강촌이 [0]인 건 이름순이 아니라 동점(0점) 안에서
			// region이 먼저 갈리기 때문 — 강원권(강촌·남춘천·춘천)이 가나다순으로 수도권보다
			// 앞서 정렬되고, 강원권 내에서는 이름순으로 강촌이 가장 앞이다.
			.andExpect(jsonPath("$.data.stationAccessibilityScoreRows[0].stationName").value("강촌"))
			.andExpect(jsonPath("$.data.stationAccessibilityScoreRows[0].reasons[0]").value("일부 정보는 확인 중이에요"))
			.andExpect(jsonPath("$.data.accessibilityImprovementPriorityRows[0].stationName").value("상록수"))
			.andExpect(jsonPath("$.data.accessibilityImprovementPriorityRows[0].facilityName").value("장애인 화장실"))
			.andExpect(jsonPath("$.data.accessibilityImprovementPriorityRows[0].priorityScore").value(60))
			.andExpect(jsonPath("$.data.accessibilityImprovementPriorityRows[0].reasons[0]").value("확인 필요 상태"))
			.andExpect(jsonPath("$.data.accessibilityImprovementPriorityRows[0].stationId").doesNotExist())
			.andExpect(jsonPath("$.data.accessibilityImprovementPriorityRows[0].facilityId").doesNotExist());
	}

	@Test
	@DisplayName("운영기관 계정은 제휴 제안용 접근성 리포트 CSV를 내려받는다")
	void operatorDownloadsPartnershipProposalCsv() throws Exception {
		mockMvc.perform(get("/operator/api/accessibility-report/proposal.csv")
				.with(httpBasic("operator-user", "operator-test-password")))
			.andExpect(status().isOk())
			.andExpect(header().string(
				HttpHeaders.CONTENT_DISPOSITION,
				"attachment; filename=\"easysubway-operator-accessibility-proposal.csv\""
			))
			.andExpect(header().string(HttpHeaders.CONTENT_TYPE, "text/csv;charset=UTF-8"))
			.andExpect(result -> {
				String csv = result.getResponse().getContentAsString();
				org.assertj.core.api.Assertions.assertThat(csv)
					.startsWith("﻿section,metric,value,detail\r\n")
					// #2095: ITX-청춘 pilot 정차역 14곳 추가로 2 → 16.
					.contains("summary,totalStations,16,")
					.contains("summary,totalFacilities,3,")
					.contains("summary,needsVerificationFacilityCount,1,")
					.contains("stationScore,상록수,")
					.contains("priority,상록수,장애인 화장실,\"60 - 확인 필요 상태");
			});
	}

	@Test
	@DisplayName("운영기관 계정은 제휴 제안 리포트를 xlsx로도 내려받는다(CSV와 동일 데이터)")
	void operatorDownloadsPartnershipProposalXlsx() throws Exception {
		byte[] xlsx = mockMvc.perform(get("/operator/api/accessibility-report/proposal.xlsx")
				.with(httpBasic("operator-user", "operator-test-password")))
			.andExpect(status().isOk())
			.andExpect(header().string(
				HttpHeaders.CONTENT_DISPOSITION,
				"attachment; filename=\"easysubway-operator-accessibility-proposal.xlsx\""
			))
			.andExpect(header().string(
				HttpHeaders.CONTENT_TYPE,
				"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
			))
			.andReturn().getResponse().getContentAsByteArray();

		try (var workbook = new XSSFWorkbook(new ByteArrayInputStream(xlsx))) {
			Sheet sheet = workbook.getSheetAt(0);
			org.assertj.core.api.Assertions.assertThat(sheet.getRow(0).getCell(0).getStringCellValue())
				.isEqualTo("section");
			org.assertj.core.api.Assertions.assertThat(sheet.getRow(1).getCell(1).getStringCellValue())
				.isEqualTo("totalStations");
			// #2095: ITX-청춘 pilot 정차역 14곳 추가로 2 → 16.
			org.assertj.core.api.Assertions.assertThat(sheet.getRow(1).getCell(2).getStringCellValue())
				.isEqualTo("16");
		}
	}

	@Test
	@DisplayName("운영기관 접근성 리포트 API는 운영기관 계정 인증을 요구한다")
	void accessibilityReportRequiresOperatorAuthentication() throws Exception {
		mockMvc.perform(get("/operator/api/accessibility-report"))
			.andExpect(status().isUnauthorized());

		mockMvc.perform(get("/operator/api/accessibility-report")
				.with(httpBasic("basic-user", "user-test-password")))
			.andExpect(status().isForbidden());

		mockMvc.perform(get("/operator/api/accessibility-report")
				.with(httpBasic("admin-user", "admin-test-password")))
			.andExpect(status().isForbidden());
	}

	@Test
	@DisplayName("제휴 제안용 CSV export는 운영기관 계정 인증을 요구한다")
	void partnershipProposalCsvRequiresOperatorAuthentication() throws Exception {
		mockMvc.perform(get("/operator/api/accessibility-report/proposal.csv"))
			.andExpect(status().isUnauthorized());

		mockMvc.perform(get("/operator/api/accessibility-report/proposal.csv")
				.with(httpBasic("basic-user", "user-test-password")))
			.andExpect(status().isForbidden());

		mockMvc.perform(get("/operator/api/accessibility-report/proposal.csv")
				.with(httpBasic("admin-user", "admin-test-password")))
			.andExpect(status().isForbidden());
	}
}
