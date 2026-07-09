package com.easysubway.operator.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
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
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
@DisplayName("운영기관 반복 고장 시설 통계 CSV")
class OperatorRepeatedBrokenFacilitiesCsvTest {

	@Autowired
	private MockMvc mockMvc;

	@Test
	@DisplayName("운영기관 계정은 반복 고장 시설을 UTF-8 BOM CSV로 내려받는다")
	void operatorDownloadsRepeatedFacilitiesAsCsv() throws Exception {
		createBrokenReport("첫 번째 엘리베이터 고장 신고");
		createBrokenReport("두 번째 엘리베이터 고장 신고");

		byte[] body = mockMvc.perform(get("/operator/api/repeated-broken-facilities.csv")
				.with(httpBasic("operator-user", "operator-test-password")))
			.andExpect(status().isOk())
			.andExpect(header().string("Content-Type", "text/csv;charset=UTF-8"))
			.andExpect(header().string(
				"Content-Disposition",
				"attachment; filename=\"easysubway-operator-repeated-broken-facilities.csv\""))
			.andReturn()
			.getResponse()
			.getContentAsByteArray();

		String csv = new String(body, StandardCharsets.UTF_8);
		assertThat(csv).startsWith("﻿");
		assertThat(csv).contains("역,시설,상태,신고 건수");
		assertThat(csv).contains("상록수,1번 출구 엘리베이터,정상,2");
		assertThat(csv).contains("\r\n");
	}

	@Test
	@DisplayName("반복 고장 시설 CSV는 운영기관 계정 인증을 요구한다")
	void csvRequiresOperatorAuthentication() throws Exception {
		mockMvc.perform(get("/operator/api/repeated-broken-facilities.csv"))
			.andExpect(status().isUnauthorized());

		mockMvc.perform(get("/operator/api/repeated-broken-facilities.csv")
				.with(httpBasic("basic-user", "user-test-password")))
			.andExpect(status().isForbidden());

		mockMvc.perform(get("/operator/api/repeated-broken-facilities.csv")
				.with(httpBasic("admin-user", "admin-test-password")))
			.andExpect(status().isForbidden());
	}

	private void createBrokenReport(String description) throws Exception {
		mockMvc.perform(post("/api/v1/reports")
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
			.andExpect(status().isCreated());
	}
}
