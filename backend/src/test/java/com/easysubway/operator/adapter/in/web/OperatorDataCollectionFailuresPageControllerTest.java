package com.easysubway.operator.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
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
@DisplayName("운영기관 데이터 수집 실패 현황 화면")
class OperatorDataCollectionFailuresPageControllerTest {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private SaveDataCollectionRunPort saveDataCollectionRunPort;

	@TestConfiguration
	static class FixedClockConfiguration {

		@Bean
		Clock operatorDataCollectionFailuresPageTestClock() {
			return Clock.fixed(Instant.parse("2026-06-18T03:00:00Z"), ZoneId.of("Asia/Seoul"));
		}
	}

	@Test
	@DisplayName("운영기관 계정은 읽기 전용 데이터 수집 실패와 미갱신 경고를 확인한다")
	void operatorGetsDataCollectionFailuresPage() throws Exception {
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

		String html = mockMvc.perform(get("/operator/data-collection-failures/page")
				.with(httpBasic("operator-user", "operator-test-password")))
			.andExpect(status().isOk())
			.andReturn()
			.getResponse()
			.getContentAsString();

		assertThat(html)
			.contains("데이터 수집 실패 보고서")
			.contains("운영기관 포털")
			.contains("전체 수집 실행")
			.contains("실패 실행")
			.contains("재시도 가능")
			.contains("데이터 갱신 상태")
			.contains("점검 필요")
			.contains("최신 완료 수집")
			.contains("2026-06-17T09:01")
			.contains("도시철도 마스터 수집 완료 기록이 24시간 이상 갱신되지 않았습니다.")
			.contains("최근 수집 실행")
			.contains("수집 원천")
			.contains("상태")
			.contains("시작 시각")
			.contains("종료 시각")
			.contains("수집 건수")
			.contains("실패 사유")
			.contains("운영 조치")
			.contains("도시철도 마스터")
			.contains("실패")
			.contains("완료")
			.contains("2026-06-18T10:00")
			.contains("2026-06-18T10:00:30")
			.contains("공공데이터 응답 지연")
			.contains("일시 오류일 수 있습니다. 실패 사유를 확인한 뒤 같은 수집 대상을 다시 실행하세요.")
			.doesNotContain("collection-failed")
			.doesNotContain("collection-completed")
			.doesNotContain("admin-user")
			.doesNotContain("name=\"_csrf\"")
			.doesNotContain("<form")
			.doesNotContain("/admin/collections");
	}

	@Test
	@DisplayName("운영기관 데이터 수집 실패 현황 화면은 운영기관 계정 인증을 요구한다")
	void dataCollectionFailuresPageRequiresOperatorAuthentication() throws Exception {
		mockMvc.perform(get("/operator/data-collection-failures/page"))
			.andExpect(status().is3xxRedirection())
			.andExpect(redirectedUrl("http://localhost/operator/login"));

		mockMvc.perform(get("/operator/data-collection-failures/page")
				.with(httpBasic("basic-user", "user-test-password")))
			.andExpect(status().isForbidden());

		mockMvc.perform(get("/operator/data-collection-failures/page")
				.with(httpBasic("admin-user", "admin-test-password")))
			.andExpect(status().isForbidden());
	}

	private void saveRun(DataCollectionRun run) {
		saveDataCollectionRunPort.saveRun(run);
	}
}
