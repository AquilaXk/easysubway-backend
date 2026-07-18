package com.easysubway.collection.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.easysubway.collection.application.port.out.SaveDataCollectionRunPort;
import com.easysubway.collection.domain.DataCollectionRun;
import com.easysubway.collection.domain.DataCollectionRunStep;
import com.easysubway.collection.domain.DataCollectionSource;
import com.easysubway.collection.domain.DataCollectionStatus;
import com.easysubway.collection.domain.DataCollectionStepStatus;
import jakarta.servlet.http.HttpSession;
import java.time.LocalDateTime;
import java.util.List;
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
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
@DisplayName("관리자 데이터 수집 배치 화면")
class DataCollectionAdminPageControllerTest {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private SaveDataCollectionRunPort saveDataCollectionRunPort;

	@Test
	@DisplayName("실패 실행은 실패 단계를 강조한 아코디언과 재시도 확인을 표시한다")
	void failedRunHighlightsFailedStepAndOffersRetry() throws Exception {
		saveDataCollectionRunPort.saveRun(failedRun("failed-run"));

		String html = mockMvc.perform(get("/admin/data-collections/page")
				.with(httpBasic("admin-user", "admin-test-password")))
			.andExpect(status().isOk())
			.andReturn()
			.getResponse()
			.getContentAsString();

		assertThat(html)
			.contains("admin-step-accordion")
			.contains("admin-step-failed")
			.contains("실패 1개")
			.contains("source timeout")
			.contains("재시도 확인")
			.contains("value=\"TRANSIT_MASTER\"");
	}

	@Test
	@DisplayName("관리자는 데이터 수집 화면에서 실행 버튼과 최근 실행 기록을 확인한다")
	void adminGetsDataCollectionPageWithRunFormAndRecentRuns() throws Exception {
		runTransitMasterCollection();

		String html = mockMvc.perform(get("/admin/data-collections/page")
				.with(httpBasic("admin-user", "admin-test-password")))
			.andExpect(status().isOk())
			.andReturn()
			.getResponse()
			.getContentAsString();

		assertThat(html)
			.contains("데이터 수집 배치")
			.contains("도시철도 마스터 데이터 수집")
			.contains("수집 실행")
			.contains("최근 실행 기록")
			.contains("완료")
			.contains("재시도")
			.contains("다음 행동")
			.contains("불필요")
			.contains("수집이 완료되었습니다. 최근 데이터 품질 화면에서 반영 결과를 확인하세요.")
			.contains("FETCH")
			.contains("STAGE")
			.contains("PUBLISH")
			.contains("건너뜀")
			.contains("수동 필요")
			.contains("admin-user")
			// #2095: InMemoryTransitMasterRepository에 ITX-청춘 pilot 정차역 14곳과
			// 이를 연결하는 ITX-청춘 노선(LINES 1건)·STATION_LINES 14건이 추가돼
			// 수집 건수(operators+lines+stations+...)가 28에서 43으로 늘었다.
			.contains(">43<")
			.contains("name=\"source\"")
			.contains("value=\"TRANSIT_MASTER\"")
			.contains("name=\"_csrf\"");
	}

	@Test
	@DisplayName("관리자는 데이터 수집 화면에서 배치를 실행한 뒤 목록으로 돌아온다")
	void adminRunsDataCollectionFromPageAndRedirectsToList() throws Exception {
		mockMvc.perform(post("/admin/data-collections/page/run")
				.with(httpBasic("admin-user", "admin-test-password"))
				.with(csrf())
				.with(commandToken("/admin/data-collections/page"))
				.contentType(MediaType.APPLICATION_FORM_URLENCODED)
				.param("source", "TRANSIT_MASTER"))
			.andExpect(status().is3xxRedirection())
			.andExpect(header().string("Location", "/admin/data-collections/page"));

		String html = mockMvc.perform(get("/admin/data-collections/page")
				.with(httpBasic("admin-user", "admin-test-password")))
			.andExpect(status().isOk())
			.andReturn()
			.getResponse()
			.getContentAsString();

		assertThat(html)
			.contains("도시철도 마스터")
			.contains("완료")
			.contains("admin-user");
	}

	@Test
	@DisplayName("기존 데이터 수집 실행 endpoint도 같은 source RUNNING claim을 우회하지 못한다")
	void pageRunRejectsRunningSource() throws Exception {
		saveDataCollectionRunPort.saveRun(runningRun("running-run"));

		mockMvc.perform(post("/admin/data-collections/page/run")
				.with(httpBasic("admin-user", "admin-test-password"))
				.with(csrf())
				.with(commandToken("/admin/data-collections/page"))
				.contentType(MediaType.APPLICATION_FORM_URLENCODED)
				.param("source", "TRANSIT_MASTER"))
			.andExpect(status().isBadRequest());
	}

	@Test
	@DisplayName("데이터 수집 실행 목록은 page size와 현재 페이지를 링크에 표시한다")
	void dataCollectionPageShowsPaginationLinks() throws Exception {
		runTransitMasterCollection();
		runTransitMasterCollection();

		String html = mockMvc.perform(get("/admin/data-collections/page")
				.param("size", "1")
				.with(httpBasic("admin-user", "admin-test-password")))
			.andExpect(status().isOk())
			.andReturn()
			.getResponse()
			.getContentAsString();

		assertThat(html)
			.contains("데이터 수집 실행 목록 페이지")
			.contains("aria-current=\"page\"")
			.contains("page=1&amp;size=1")
			.contains("다음");
	}

	@Test
	@DisplayName("관리자 데이터 수집 화면은 관리자 인증을 요구한다")
	void dataCollectionPagesRequireAdminAuthentication() throws Exception {
		mockMvc.perform(get("/admin/data-collections/page"))
			.andExpect(status().isUnauthorized());

		mockMvc.perform(get("/admin/data-collections/page")
				.with(httpBasic("basic-user", "user-test-password")))
			.andExpect(status().isForbidden());

		mockMvc.perform(post("/admin/data-collections/page/run")
				.with(csrf())
				.contentType(MediaType.APPLICATION_FORM_URLENCODED)
				.param("source", "TRANSIT_MASTER"))
			.andExpect(status().isUnauthorized());

		mockMvc.perform(post("/admin/data-collections/page/run")
				.with(httpBasic("basic-user", "user-test-password"))
				.with(csrf())
				.contentType(MediaType.APPLICATION_FORM_URLENCODED)
				.param("source", "TRANSIT_MASTER"))
			.andExpect(status().isForbidden());
	}

	@Test
	@DisplayName("실행 중 수집이 있으면 live 폴러가 활성이고 fragment는 셸 없이 반환된다")
	void collectionLiveActiveWhenRunning() throws Exception {
		saveDataCollectionRunPort.saveRun(runningRun("running-run"));

		String page = mockMvc.perform(get("/admin/data-collections/page")
				.with(httpBasic("admin-user", "admin-test-password")))
			.andExpect(status().isOk())
			.andReturn()
			.getResponse()
			.getContentAsString();
		assertThat(page)
			.contains("x-data=\"autoRefresh\"")
			.contains("data-refresh-url=\"/admin/data-collections/page/live\"")
			.contains("data-refresh-interval=\"10000\"")
			.contains("data-refresh-active=\"true\"");

		String fragment = mockMvc.perform(get("/admin/data-collections/page/live")
				.with(httpBasic("admin-user", "admin-test-password")))
			.andExpect(status().isOk())
			.andReturn()
			.getResponse()
			.getContentAsString();
		assertThat(fragment)
			.contains("id=\"collection-live\"")
			.contains("최근 실행 기록")
			.doesNotContain("admin-shell")
			.doesNotContain("class=\"run-form\"");
	}

	private DataCollectionRun runningRun(String runId) {
		LocalDateTime now = LocalDateTime.now();
		return new DataCollectionRun(
			runId,
			DataCollectionSource.TRANSIT_MASTER,
			DataCollectionStatus.RUNNING,
			"admin-user",
			now,
			null,
			0,
			null,
			false,
			"수집이 진행 중입니다.",
			List.of(new DataCollectionRunStep("FETCH", DataCollectionStepStatus.COMPLETED, null, null, null, 1, null))
		);
	}

	private DataCollectionRun failedRun(String runId) {
		LocalDateTime now = LocalDateTime.of(2026, 6, 27, 0, 0);
		return new DataCollectionRun(
			runId,
			DataCollectionSource.TRANSIT_MASTER,
			DataCollectionStatus.FAILED,
			"admin-user",
			now,
			now.plusMinutes(1),
			0,
			"FETCH 실패",
			true,
			"원인 확인 후 재시도하세요.",
			List.of(
				new DataCollectionRunStep("FETCH", DataCollectionStepStatus.FAILED, null, null, null, 0, "source timeout"),
				new DataCollectionRunStep("STAGE", DataCollectionStepStatus.SKIPPED, null, null, null, 0, null)
			)
		);
	}

	private void runTransitMasterCollection() throws Exception {
		mockMvc.perform(post("/admin/data-collections/runs")
				.with(httpBasic("admin-user", "admin-test-password"))
				.with(csrf())
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "source": "TRANSIT_MASTER"
					}
					"""))
			.andExpect(status().isOk());
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
