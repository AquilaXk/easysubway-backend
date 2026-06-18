package com.easysubway.operator.adapter.in.web;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.easysubway.collection.application.port.out.SaveDataCollectionRunPort;
import com.easysubway.collection.domain.DataCollectionRun;
import com.easysubway.collection.domain.DataCollectionSource;
import com.easysubway.collection.domain.DataCollectionStatus;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
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
@DisplayName("운영기관 데이터 수집 실패 현황 API")
class OperatorDataCollectionFailuresControllerTest {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private SaveDataCollectionRunPort saveDataCollectionRunPort;

	@TestConfiguration
	static class FixedClockConfiguration {

		@Bean
		Clock operatorDataCollectionFailuresTestClock() {
			return Clock.fixed(Instant.parse("2026-06-18T03:00:00Z"), ZoneId.of("Asia/Seoul"));
		}
	}

	@Test
	@DisplayName("운영기관 계정은 최근 데이터 수집 실패와 미갱신 경고를 JSON으로 조회한다")
	void operatorGetsDataCollectionFailures() throws Exception {
		saveRun(new DataCollectionRun(
			"collection-completed",
			DataCollectionSource.TRANSIT_MASTER,
			DataCollectionStatus.COMPLETED,
			"admin-user",
			LocalDateTime.parse("2026-06-17T09:00:00"),
			LocalDateTime.parse("2026-06-17T09:01:00"),
			14,
			null,
			false,
			"수집이 완료되었습니다. 최근 데이터 품질 화면에서 반영 결과를 확인하세요."
		));
		saveRun(new DataCollectionRun(
			"collection-failed",
			DataCollectionSource.TRANSIT_MASTER,
			DataCollectionStatus.FAILED,
			"admin-user",
			LocalDateTime.parse("2026-06-18T10:00:00"),
			LocalDateTime.parse("2026-06-18T10:00:30"),
			0,
			"공공데이터 응답 지연",
			true,
			"일시 오류일 수 있습니다. 실패 사유를 확인한 뒤 같은 수집 대상을 다시 실행하세요."
		));

		mockMvc.perform(get("/operator/api/data-collection-failures")
				.with(httpBasic("operator-user", "operator-test-password")))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.success").value(true))
			.andExpect(jsonPath("$.data.totalRunCount").value(2))
			.andExpect(jsonPath("$.data.failedRunCount").value(1))
			.andExpect(jsonPath("$.data.retryableRunCount").value(1))
			.andExpect(jsonPath("$.data.latestCompletedAtLabel").value("2026-06-17T09:01"))
			.andExpect(jsonPath("$.data.freshnessAlertLabel").value("점검 필요"))
			.andExpect(jsonPath("$.data.freshnessAlertDescription")
				.value("도시철도 마스터 수집 완료 기록이 24시간 이상 갱신되지 않았습니다."))
			.andExpect(jsonPath("$.data.freshnessAlertClass").value("stale"))
			.andExpect(jsonPath("$.data.rows[0].sourceLabel").value("도시철도 마스터"))
			.andExpect(jsonPath("$.data.rows[0].statusLabel").value("실패"))
			.andExpect(jsonPath("$.data.rows[0].startedAtLabel").value("2026-06-18T10:00"))
			.andExpect(jsonPath("$.data.rows[0].completedAtLabel").value("2026-06-18T10:00:30"))
			.andExpect(jsonPath("$.data.rows[0].collectedCount").value(0))
			.andExpect(jsonPath("$.data.rows[0].failureMessage").value("공공데이터 응답 지연"))
			.andExpect(jsonPath("$.data.rows[0].retryable").value(true))
			.andExpect(jsonPath("$.data.rows[0].operatorAction")
				.value("일시 오류일 수 있습니다. 실패 사유를 확인한 뒤 같은 수집 대상을 다시 실행하세요."))
			.andExpect(jsonPath("$.data.rows[0].runId").doesNotExist())
			.andExpect(jsonPath("$.data.rows[0].requestedBy").doesNotExist())
			.andExpect(jsonPath("$.data.rows[1].statusLabel").value("완료"))
			.andExpect(jsonPath("$.data.rows[1].failureMessage").value("-"))
			.andExpect(jsonPath("$.data.rows[1].retryable").value(false));
	}

	@Test
	@DisplayName("운영기관 데이터 수집 실패 현황 API는 최신 완료 수집이 24시간 이내면 정상으로 표시한다")
	void dataCollectionFreshnessIsOkWithinADay() throws Exception {
		saveRun(new DataCollectionRun(
			"collection-completed",
			DataCollectionSource.TRANSIT_MASTER,
			DataCollectionStatus.COMPLETED,
			"admin-user",
			LocalDateTime.parse("2026-06-18T10:00:00"),
			LocalDateTime.parse("2026-06-18T10:01:00"),
			14,
			null,
			false,
			"수집이 완료되었습니다. 최근 데이터 품질 화면에서 반영 결과를 확인하세요."
		));

		mockMvc.perform(get("/operator/api/data-collection-failures")
				.with(httpBasic("operator-user", "operator-test-password")))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.success").value(true))
			.andExpect(jsonPath("$.data.latestCompletedAtLabel").value("2026-06-18T10:01"))
			.andExpect(jsonPath("$.data.freshnessAlertLabel").value("정상"))
			.andExpect(jsonPath("$.data.freshnessAlertDescription")
				.value("최근 24시간 이내 데이터 수집 완료 기록이 있습니다."))
			.andExpect(jsonPath("$.data.freshnessAlertClass").value("ok"));
	}

	@Test
	@DisplayName("운영기관 데이터 수집 실패 현황 API는 표시 목록 밖의 최신 완료 수집으로 미갱신 상태를 판단한다")
	void dataCollectionFreshnessUsesLatestCompletedRunOutsideRecentRows() throws Exception {
		saveRun(new DataCollectionRun(
			"collection-completed-outside-recent-rows",
			DataCollectionSource.TRANSIT_MASTER,
			DataCollectionStatus.COMPLETED,
			"admin-user",
			LocalDateTime.parse("2026-06-18T09:00:00"),
			LocalDateTime.parse("2026-06-18T09:01:00"),
			14,
			null,
			false,
			"수집이 완료되었습니다. 최근 데이터 품질 화면에서 반영 결과를 확인하세요."
		));
		for (int index = 0; index < 20; index += 1) {
			saveRun(new DataCollectionRun(
				"collection-failed-" + index,
				DataCollectionSource.TRANSIT_MASTER,
				DataCollectionStatus.FAILED,
				"admin-user",
				LocalDateTime.parse("2026-06-18T09:10:00").plusMinutes(index),
				LocalDateTime.parse("2026-06-18T09:10:30").plusMinutes(index),
				0,
				"공공데이터 응답 지연",
				true,
				"일시 오류일 수 있습니다. 실패 사유를 확인한 뒤 같은 수집 대상을 다시 실행하세요."
			));
		}

		mockMvc.perform(get("/operator/api/data-collection-failures")
				.with(httpBasic("operator-user", "operator-test-password")))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.totalRunCount").value(20))
			.andExpect(jsonPath("$.data.latestCompletedAtLabel").value("2026-06-18T09:01"))
			.andExpect(jsonPath("$.data.freshnessAlertLabel").value("정상"))
			.andExpect(jsonPath("$.data.freshnessAlertClass").value("ok"))
			.andExpect(jsonPath("$.data.rows[0].statusLabel").value("실패"))
			.andExpect(jsonPath("$.data.rows[19].statusLabel").value("실패"));
	}

	@Test
	@DisplayName("운영기관 데이터 수집 실패 현황 API는 최신 완료 수집이 정확히 24시간이면 점검 필요로 표시한다")
	void dataCollectionFreshnessRequiresInspectionAtBoundary24Hours() throws Exception {
		saveRun(new DataCollectionRun(
			"collection-completed-boundary",
			DataCollectionSource.TRANSIT_MASTER,
			DataCollectionStatus.COMPLETED,
			"admin-user",
			LocalDateTime.parse("2026-06-17T11:59:00"),
			LocalDateTime.parse("2026-06-17T12:00:00"),
			14,
			null,
			false,
			"수집이 완료되었습니다. 최근 데이터 품질 화면에서 반영 결과를 확인하세요."
		));

		mockMvc.perform(get("/operator/api/data-collection-failures")
				.with(httpBasic("operator-user", "operator-test-password")))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.latestCompletedAtLabel").value("2026-06-17T12:00"))
			.andExpect(jsonPath("$.data.freshnessAlertLabel").value("점검 필요"))
			.andExpect(jsonPath("$.data.freshnessAlertClass").value("stale"));
	}

	@Test
	@DisplayName("운영기관 데이터 수집 실패 현황 API는 운영기관 계정 인증을 요구한다")
	void dataCollectionFailuresRequireOperatorAuthentication() throws Exception {
		mockMvc.perform(get("/operator/api/data-collection-failures"))
			.andExpect(status().isUnauthorized());

		mockMvc.perform(get("/operator/api/data-collection-failures")
				.with(httpBasic("basic-user", "user-test-password")))
			.andExpect(status().isForbidden());

		mockMvc.perform(get("/operator/api/data-collection-failures")
				.with(httpBasic("admin-user", "admin-test-password")))
			.andExpect(status().isForbidden());
	}

	private void saveRun(DataCollectionRun run) {
		saveDataCollectionRunPort.saveRun(run);
	}
}
