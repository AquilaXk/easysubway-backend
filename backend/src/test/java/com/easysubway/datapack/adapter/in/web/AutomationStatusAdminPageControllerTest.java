package com.easysubway.datapack.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.easysubway.datapack.application.service.AutomationStatusFixtures;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {
	"easysubway.admin.username=admin-user",
	"easysubway.admin.password=admin-test-password"
})
@AutoConfigureMockMvc
@DisplayName("관리자 자동화 상태 화면")
class AutomationStatusAdminPageControllerTest {

	private static final String PATH = "/admin/datapack/automation/page";

	@Autowired
	private MockMvc mockMvc;
	@Autowired
	private JdbcTemplate jdbcTemplate;

	@BeforeEach
	void setUp() {
		jdbcTemplate.update("DELETE FROM datapack_automation_status");
	}

	private String page(String authority) throws Exception {
		return mockMvc.perform(get(PATH).with(user("viewer").authorities(new SimpleGrantedAuthority(authority))))
			.andExpect(status().isOk())
			.andReturn().getResponse().getContentAsString();
	}

	private void store(String json, Instant generatedAt, Instant receivedAt) {
		jdbcTemplate.update(
			"INSERT INTO datapack_automation_status (id, payload_json, generated_at, received_at) VALUES (1, ?, ?, ?)",
			json, OffsetDateTime.ofInstant(generatedAt, ZoneOffset.UTC), OffsetDateTime.ofInstant(receivedAt, ZoneOffset.UTC));
	}

	@Test
	@DisplayName("datapack read 권한이 없으면 화면을 볼 수 없다")
	void requiresDatapackReadPermission() throws Exception {
		mockMvc.perform(get(PATH).with(user("viewer").authorities(new SimpleGrantedAuthority("admin.view"))))
			.andExpect(status().isForbidden());
	}

	@Test
	@DisplayName("snapshot을 받은 적이 없으면 정상이 아니라 수신 전으로 보인다")
	void showsNotReceivedWhenNothingStored() throws Exception {
		String html = page("admin.datapack.read");

		assertThat(html).contains("자동화 상태").contains("수신 전").contains("자동화 상태를 아직 받지 못했습니다");
		assertThat(html).doesNotContain("모든 자동화 단계가 정상");
	}

	@Test
	@DisplayName("정상 snapshot은 활성 데이터팩, 단계별 최근 실행과 링크를 보여준다")
	void showsHealthyStatus() throws Exception {
		Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
		String json = AutomationStatusFixtures.validJson(now.minusSeconds(120).toString(), now.plus(90, ChronoUnit.HOURS).toString(), "[]", "[]", "false");
		store(json, now.minusSeconds(120), now.minusSeconds(60));

		String html = page("admin.datapack.read");

		assertThat(html)
			.contains("실행 기록이 있는 단계에 이상이 없습니다")
			.contains("활성 데이터팩")
			.contains(">129<")
			.contains("단계별 최근 실행")
			.contains("원천 갱신")
			.contains("https://github.com/AquilaXk/easysubway-data/actions/runs/101")
			.contains("https://github.com/AquilaXk/easysubway-platform/actions/runs/303")
			.contains("진행 중")
			.contains("기록 없음")
			.contains("열린 자동화 실패 이슈")
			.contains("막힌 자동화");
		assertThat(html).doesNotContain("게시가 멈췄습니다").doesNotContain("만료가 가까워지고 있습니다");
	}

	@Test
	@DisplayName("실패 이슈와 막힌 PR이 있으면 링크와 함께 이상으로 드러난다")
	void showsAnomalies() throws Exception {
		Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
		String json = AutomationStatusFixtures.validJson(now.minusSeconds(120).toString(), now.plus(90, ChronoUnit.HOURS).toString(),
			AutomationStatusFixtures.failureIssueJson(55), AutomationStatusFixtures.stuckPullJson(), "false");
		store(json, now.minusSeconds(120), now.minusSeconds(60));

		String html = page("admin.datapack.read");

		assertThat(html)
			.contains("열린 자동화 실패 이슈 1건")
			.contains("https://github.com/AquilaXk/easysubway-data/issues/55")
			.contains("https://github.com/AquilaXk/easysubway-data/pull/7")
			.contains("뒤처짐");
		// 가장 심한 이상은 요약 한 줄이고, 목록은 나머지만 보여 같은 문장이 두 번 나오지 않는다.
		assertThat(html.split("열린 자동화 실패 이슈 1건", -1)).hasSize(2);
		assertThat(html).contains("막힌 자동화 1건");
	}

	@Test
	@DisplayName("만료가 36시간 안으로 다가왔고 진행 중인 후보가 없으면 경고하고, 수신이 멈췄으면 그것을 알린다")
	void showsExpiryWarningAndStalePublishing() throws Exception {
		Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
		String json = AutomationStatusFixtures.validJson(now.minusSeconds(120).toString(), now.plus(20, ChronoUnit.HOURS).toString(), "[]", "[]", "false");
		store(json, now.minusSeconds(120), now.minusSeconds(60));
		assertThat(page("admin.datapack.read")).contains("만료가 가까워지고 있습니다");

		jdbcTemplate.update("DELETE FROM datapack_automation_status");
		store(json, now.minusSeconds(7200), now.minus(2, ChronoUnit.HOURS));
		assertThat(page("admin.datapack.read")).contains("게시가 멈췄습니다");
	}

	@Test
	@DisplayName("단계의 최근 실행이 실패했거나 단계 목록이 비어 있으면 정상 요약 대신 그 단계가 이상으로 보인다")
	void showsStageFailureAndMissingStages() throws Exception {
		Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
		String expires = now.plus(90, ChronoUnit.HOURS).toString();
		String failed = AutomationStatusFixtures.validJsonWithStages(now.minusSeconds(120).toString(), expires,
			AutomationStatusFixtures.stagesJson(java.util.Map.of("publish", "failure")), "[]", "[]", "false");
		store(failed, now.minusSeconds(120), now.minusSeconds(60));
		String failedHtml = page("admin.datapack.read");
		assertThat(failedHtml).contains("발행 단계의 마지막 실행이 실패했습니다(failure)").doesNotContain("실행 기록이 있는 단계에 이상이 없습니다");

		jdbcTemplate.update("DELETE FROM datapack_automation_status");
		String empty = AutomationStatusFixtures.validJsonWithStages(now.minusSeconds(120).toString(), expires, "[]", "[]", "[]", "false");
		store(empty, now.minusSeconds(120), now.minusSeconds(60));
		assertThat(page("admin.datapack.read")).contains("자동화 단계 정보가 빠져 있습니다").contains("원천 갱신").contains("받은 단계 정보가 없습니다").doesNotContain("이상이 없습니다");
	}

	private void storeWithSources(Instant now, String sources) {
		store(AutomationStatusFixtures.validJsonWithStagesAndSources(now.minusSeconds(120).toString(), now.plus(90, ChronoUnit.HOURS).toString(),
			AutomationStatusFixtures.healthyStagesJson(), "[]", "[]", "false", sources), now.minusSeconds(120), now.minusSeconds(60));
	}

	@Test
	@DisplayName("원천 근거 만료 정보를 보내지 않는 snapshot에서는 표 대신 아직 받지 못했다고 보이고 정상 문구로 채우지 않는다")
	void showsSourceExpiryNotReportedForOldSnapshots() throws Exception {
		Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
		storeWithSources(now, null);

		String html = page("admin.datapack.read");

		assertThat(html).contains("곧 만료되는 원천 근거").contains("원천 근거 만료 정보를 아직 받지 못했습니다");
		assertThat(html).doesNotContain("곧 만료되는 원천 근거가 없습니다").doesNotContain("남은 시간");
		assertThat(html).contains("실행 기록이 있는 단계에 이상이 없습니다");
	}

	@Test
	@DisplayName("원천 근거 목록이 비어 있으면 곧 만료되는 근거가 없다고 보인다")
	void showsNoExpiringSources() throws Exception {
		storeWithSources(Instant.now().truncatedTo(ChronoUnit.SECONDS), "[]");

		assertThat(page("admin.datapack.read")).contains("곧 만료되는 원천 근거가 없습니다").doesNotContain("원천 근거 만료 정보를 아직 받지 못했습니다");
	}

	@Test
	@DisplayName("12시간 안에 만료되는 근거는 주의로 보이고 자동 갱신 경로가 없으면 그렇다고 적는다")
	void showsExpiringSourceWarning() throws Exception {
		Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
		storeWithSources(now, "[" + AutomationStatusFixtures.expiringSourceJson("busan-transportation-timetable", "부산 도시철도 시간표",
			now.plus(8, ChronoUnit.HOURS).plusSeconds(30).toString(), null, "NONE") + ","
			+ AutomationStatusFixtures.expiringSourceJson("daegu-line1-train-timetable", "대구 1호선 열차 시간표",
			now.plus(3, ChronoUnit.DAYS).toString(), "source-reverification", "OK") + "]");

		String html = page("admin.datapack.read");

		assertThat(html)
			.contains("원천 자료 1건이 12시간 안에 만료됩니다: 부산 도시철도 시간표(8시간 0분 남음, 자동 갱신 없음)")
			.contains("부산 도시철도 시간표").contains("8시간 0분 남음").contains("자동 갱신 없음")
			.contains("대구 1호선 열차 시간표").contains("갱신 작업 정상");
		assertThat(html).doesNotContain("실행 기록이 있는 단계에 이상이 없습니다");
	}

	@Test
	@DisplayName("6시간 안에 만료되는데 갱신 작업이 막혀 있으면 이상으로 보인다")
	void showsExpiringSourceFailure() throws Exception {
		Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
		storeWithSources(now, "[" + AutomationStatusFixtures.expiringSourceJson("incheon-line1-train-timetable", "인천 1호선 열차 시간표",
			now.plus(2, ChronoUnit.HOURS).plusSeconds(30).toString(), "capital-topology-refresh", "BLOCKED") + ","
			+ AutomationStatusFixtures.expiringSourceJson("incheon-line2-train-timetable", "인천 2호선 열차 시간표",
			now.minus(20, ChronoUnit.MINUTES).toString(), "capital-topology-refresh", "FAILED") + "]");

		String html = page("admin.datapack.read");

		assertThat(html)
			.contains("원천 자료 2건이 6시간 안에 만료되는데 갱신 작업이 실패했거나 막혀 있습니다: 인천 2호선 열차 시간표(만료됨, 20분 지남), 인천 1호선 열차 시간표(2시간 0분 남음)")
			.contains("갱신 작업이 막혀 있습니다").contains("갱신 작업이 실패했습니다").contains("만료됨 (20분 지남)").contains("2시간 0분 남음");
		// 표는 보낸 순서가 아니라 만료가 이른 순서다.
		String table = html.substring(html.indexOf("<th scope=\"col\">자료</th>"));
		assertThat(table.indexOf("인천 2호선 열차 시간표")).isPositive().isLessThan(table.indexOf("인천 1호선 열차 시간표"));
	}
}
