package com.easysubway.operator.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.easysubway.collection.application.port.out.SaveDataCollectionRunPort;
import com.easysubway.collection.domain.DataCollectionRun;
import com.easysubway.collection.domain.DataCollectionSource;
import com.easysubway.collection.domain.DataCollectionStatus;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
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
@DisplayName("운영기관 데이터 수집 실패 현황 CSV")
class OperatorDataCollectionFailuresCsvTest {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private SaveDataCollectionRunPort saveDataCollectionRunPort;

	@Test
	@DisplayName("운영기관 계정은 수집 실행 이력을 UTF-8 BOM CSV로 내려받는다")
	void operatorDownloadsRunsAsCsv() throws Exception {
		saveDataCollectionRunPort.saveRun(new DataCollectionRun(
			"collection-failed",
			DataCollectionSource.TRANSIT_MASTER,
			DataCollectionStatus.FAILED,
			"admin-user",
			LocalDateTime.parse("2026-06-18T10:00:00"),
			LocalDateTime.parse("2026-06-18T10:00:30"),
			0,
			"공공데이터 응답 지연",
			true,
			"실패 사유를 확인한 뒤 같은 수집 대상을 다시 실행하세요."
		));

		byte[] body = mockMvc.perform(get("/operator/api/data-collection-failures.csv")
				.with(httpBasic("operator-user", "operator-test-password")))
			.andExpect(status().isOk())
			.andExpect(header().string("Content-Type", "text/csv;charset=UTF-8"))
			.andExpect(header().string(
				"Content-Disposition",
				"attachment; filename=\"easysubway-operator-data-collection-failures.csv\""))
			.andReturn()
			.getResponse()
			.getContentAsByteArray();

		String csv = new String(body, StandardCharsets.UTF_8);
		assertThat(csv).startsWith("﻿");
		assertThat(csv).contains("수집 대상,상태,시작,완료,수집 건수,실패 사유,재시도 가능,운영 안내");
		assertThat(csv).contains("도시철도 마스터,실패,");
		assertThat(csv).contains("공공데이터 응답 지연");
		assertThat(csv).contains("\r\n");
	}

	@Test
	@DisplayName("데이터 수집 실패 CSV는 운영기관 계정 인증을 요구한다")
	void csvRequiresOperatorAuthentication() throws Exception {
		mockMvc.perform(get("/operator/api/data-collection-failures.csv"))
			.andExpect(status().isUnauthorized());

		mockMvc.perform(get("/operator/api/data-collection-failures.csv")
				.with(httpBasic("basic-user", "user-test-password")))
			.andExpect(status().isForbidden());

		mockMvc.perform(get("/operator/api/data-collection-failures.csv")
				.with(httpBasic("admin-user", "admin-test-password")))
			.andExpect(status().isForbidden());
	}
}
