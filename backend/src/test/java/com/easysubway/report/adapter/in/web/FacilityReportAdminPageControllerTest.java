package com.easysubway.report.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.easysubway.admin.authorization.AdminPermission;
import com.easysubway.admin.audit.adapter.out.persistence.InMemoryAdminAuditEventRepository;
import com.easysubway.admin.audit.domain.AdminAuditOutcome;
import com.easysubway.admin.audit.domain.AdminAuditEventType;
import com.jayway.jsonpath.JsonPath;
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
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

@SpringBootTest(properties = {
	"easysubway.admin.username=admin-test",
	"easysubway.admin.password=admin-test-password",
	"easysubway.user.username=basic-user",
	"easysubway.user.password=user-test-password"
})
@AutoConfigureMockMvc
@DisplayName("관리자 시설 신고 화면")
class FacilityReportAdminPageControllerTest {

	private static final String VALID_PNG_BASE64 =
		"iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+/p9sAAAAASUVORK5CYII=";

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private InMemoryAdminAuditEventRepository auditEventRepository;

	@Test
	@DisplayName("관리자는 신고 목록 화면에서 접수 상태와 상세 링크를 확인한다")
	void adminReportListPageShowsReportsAndDetailLinks() throws Exception {
		String reportId = createReport("관리자 목록에서 볼 신고");

		String html = mockMvc.perform(get("/admin/reports/page")
				.with(httpBasic("admin-test", "admin-test-password")))
			.andExpect(status().isOk())
			.andReturn()
			.getResponse()
			.getContentAsString();

		assertThat(html)
			.contains("시설 신고 확인")
			.contains("접수됨")
			.contains("관리자 목록에서 볼 신고")
			.contains("/admin/reports/%s/page".formatted(reportId))
			.contains("status=SUBMITTED")
			.contains("신고 처리 시간");
	}

	@Test
	@DisplayName("관리자는 유형·사진·역 필터로 신고 대기열을 좁힌다")
	void adminReportListPageFiltersByTypeStationAndPhoto() throws Exception {
		createReportWithPhotoAndLocation("사진 있는 고장 신고");
		createReport("사진 없는 정보 오류 신고", "INFORMATION_WRONG", "");

		// 유형 필터: INFORMATION_WRONG만 남기고 필터·페이지 링크에 type 파라미터가 유지된다.
		String typeFiltered = mockMvc.perform(get("/admin/reports/page")
				.param("type", "INFORMATION_WRONG")
				.with(httpBasic("admin-test", "admin-test-password")))
			.andExpect(status().isOk())
			.andReturn().getResponse().getContentAsString();
		assertThat(typeFiltered)
			.contains("사진 없는 정보 오류 신고")
			.doesNotContain("사진 있는 고장 신고")
			.contains("type=INFORMATION_WRONG");

		// 사진 유무 필터: 사진 있는 신고만 남는다.
		String photoFiltered = mockMvc.perform(get("/admin/reports/page")
				.param("hasPhoto", "true")
				.with(httpBasic("admin-test", "admin-test-password")))
			.andExpect(status().isOk())
			.andReturn().getResponse().getContentAsString();
		assertThat(photoFiltered)
			.contains("사진 있는 고장 신고")
			.doesNotContain("사진 없는 정보 오류 신고")
			.contains("hasPhoto=true");

		// 역 필터: 매칭되지 않는 역으로 좁히면 빈 상태 + 제거 가능한 역 칩이 뜬다.
		String stationFiltered = mockMvc.perform(get("/admin/reports/page")
				.param("station", "station-none")
				.with(httpBasic("admin-test", "admin-test-password")))
			.andExpect(status().isOk())
			.andReturn().getResponse().getContentAsString();
		assertThat(stationFiltered)
			.contains("확인할 신고가 없습니다.")
			.contains("역: station-none");
	}

	@Test
	@DisplayName("신고 대기열은 키보드 단축키 도움말과 버튼 대체 수단을 함께 제공한다")
	void adminReportListPageExposesKeyboardShortcutAffordances() throws Exception {
		createReport("단축키 대상 신고");

		String html = mockMvc.perform(get("/admin/reports/page")
				.with(httpBasic("admin-test", "admin-test-password")))
			.andExpect(status().isOk())
			.andReturn().getResponse().getContentAsString();

		assertThat(html)
			// 키보드 리스너·행 마커·단축키 도움말이 렌더된다(런타임 키 동작은 #1749 접근성 QA에서 검증).
			.contains("x-on:keydown.window=\"handleKey\"")
			.contains("class=\"report-row\"")
			.contains("키보드 단축키")
			// 스크린리더·no-JS를 위해 모든 단축키 동작은 버튼으로도 존재한다: 상세(o)·승인(a)·반려(r)·도움말(?).
			.contains(">상세 보기</a>")
			.contains("value=\"ACCEPT\"")
			.contains("value=\"REJECT\"")
			.contains("단축키 도움말 열기");
	}

	@Test
	@DisplayName("관리자는 신고 목록 화면에서 다음 페이지로 이동한다")
	void adminReportListPageShowsNextPageLink() throws Exception {
		createReport("페이지 이동 신고 1");
		createReport("페이지 이동 신고 2");

		String html = mockMvc.perform(get("/admin/reports/page")
				.param("size", "1")
				.with(httpBasic("admin-test", "admin-test-password")))
			.andExpect(status().isOk())
			.andReturn()
			.getResponse()
			.getContentAsString();

		assertThat(html)
			.contains("신고 목록 페이지")
			.contains(">1</a>")
			.contains("다음")
			.contains("page=1")
			.contains("size=1");
	}

	@Test
	@DisplayName("관리자는 번호 페이지 링크에서 상태와 크기를 유지한다")
	void adminReportListPageShowsNumberedPageLinks() throws Exception {
		createReport("번호 페이지 신고 1");
		createReport("번호 페이지 신고 2");

		String html = mockMvc.perform(get("/admin/reports/page")
				.param("status", "SUBMITTED")
				.param("size", "1")
				.with(httpBasic("admin-test", "admin-test-password")))
			.andExpect(status().isOk())
			.andReturn()
			.getResponse()
			.getContentAsString();

		assertThat(html)
			.contains("신고 목록 페이지")
			.contains("aria-current=\"page\"")
			.contains("status=SUBMITTED")
			.contains("size=1")
			.contains(">1</a>")
			.contains(">2</a>");
	}

	@Test
	@DisplayName("관리자 신고 목록은 빈 결과에서 의미 없는 번호 링크를 숨긴다")
	void adminReportListPageHidesPaginationForEmptyResult() throws Exception {
		String html = mockMvc.perform(get("/admin/reports/page")
				.param("status", "RESOLVED")
				.with(httpBasic("admin-test", "admin-test-password")))
			.andExpect(status().isOk())
			.andReturn()
			.getResponse()
			.getContentAsString();

		assertThat(html)
			.contains("확인할 신고가 없습니다.")
			.doesNotContain("신고 목록 페이지");
	}

	@Test
	@DisplayName("관리자 신고 목록은 범위를 벗어난 page를 보정 URL로 돌려보낸다")
	void adminReportListPageRedirectsOutOfRangePage() throws Exception {
		mockMvc.perform(get("/admin/reports/page")
				.param("status", "RESOLVED")
				.param("page", "99")
				.param("size", "1")
				.with(httpBasic("admin-test", "admin-test-password")))
			.andExpect(status().is3xxRedirection())
			.andExpect(header().string("Location", "/admin/reports/page?status=RESOLVED&page=0&size=1"));
	}

	@Test
	@DisplayName("관리자는 신고 목록 화면에서 최근 24시간 신고 급증 경고를 확인한다")
	void adminReportListPageShowsRecentReportSurgeAlert() throws Exception {
		for (int index = 1; index <= 10; index++) {
			createReport("최근 급증 신고 %02d".formatted(index));
		}

		String html = mockMvc.perform(get("/admin/reports/page")
				.with(httpBasic("admin-test", "admin-test-password")))
			.andExpect(status().isOk())
			.andReturn()
			.getResponse()
			.getContentAsString();

		assertThat(html)
			.contains("신고 급증")
			.contains("시설 신고 확인")
			.doesNotContain("상태·사진·위치·접수일 기준으로 제보를 확인 대기열에 배치합니다.")
			// V6-08 #2280 action 체계: 승인 primary, 반려 danger로 위계를 명시한다.
			.contains("class=\"primary\" type=\"submit\" name=\"decision\" value=\"ACCEPT\">선택 승인")
			.contains("class=\"danger\" type=\"submit\" name=\"decision\" value=\"REJECT\">선택 반려")
			.contains("점검 필요")
			.contains("신고가 평소보다 많습니다")
			.containsPattern("최근 24시간 신고 \\d+건");
	}

	@Test
	@DisplayName("관리자는 신고 상세 화면에서 사진과 위치와 검수 버튼을 확인한다")
	void adminReportDetailPageShowsPhotoLocationAndReviewActions() throws Exception {
		String reportId = createReportWithPhotoAndLocation("상세에서 확인할 신고");

		String html = mockMvc.perform(get("/admin/reports/{reportId}/page", reportId)
				.with(httpBasic("admin-test", "admin-test-password")))
			.andExpect(status().isOk())
			.andReturn()
			.getResponse()
			.getContentAsString();

		assertThat(html)
			.contains("신고 상세")
			.contains("상세에서 확인할 신고")
			.contains("elevator-notice.png")
			.contains("/admin/reports/%s/photo/thumbnail".formatted(reportId))
			.contains("/admin/datapack/manual-overrides/page")
			.contains("entityId=facility-sangnoksu-elevator-1")
			.contains("reason=%EC%8B%A0%EA%B3%A0%20%EA%B2%80%EC%88%98%20%ED%9B%84%20%EC%9E%84%EC%8B%9C%20override")
			.contains("evidenceUri=/admin/reports/%s/page".formatted(reportId))
			.doesNotContain("객체 키")
			.doesNotContain("facility-reports/")
			.contains("37.302421")
			.contains("126.866221")
			// 버튼 위계 계약(V6-08 #2280, outline 이관 #2313): 승인 primary, 반려 danger, 중복 처리 outline으로 위계를 명시한다.
			.contains("class=\"primary\" type=\"submit\" name=\"decision\" value=\"ACCEPT\"")
			.contains("class=\"danger\" type=\"submit\" name=\"decision\" value=\"REJECT\"")
			.contains("class=\"outline\" type=\"submit\" name=\"decision\" value=\"MARK_DUPLICATE\"");
		assertThat(auditEventRepository.findRecent(AdminAuditEventType.PRIVACY_READ, 1))
			.singleElement()
				.satisfies(event -> {
					assertThat(event.actor()).isEqualTo("admin-test");
					assertThat(event.targetType()).isEqualTo("FACILITY_REPORT");
					assertThat(event.targetId()).isEqualTo(reportId);
					assertThat(event.action()).isEqualTo("VIEW_REPORT_DETAIL");
					assertThat(event.reason()).contains("신고 상세 조회");
				});
	}

	@Test
	@DisplayName("신고 상세는 역·시설 이름을 보여주고 같은 시설 신고 목록을 노출한다")
	void adminReportDetailShowsLabelsAndSameFacilityReports() throws Exception {
		String first = createReport("첫 신고");
		String second = createReport("같은 시설 두 번째 신고");

		String html = mockMvc.perform(get("/admin/reports/{reportId}/page", first)
				.with(httpBasic("admin-test", "admin-test-password")))
			.andExpect(status().isOk())
			.andReturn().getResponse().getContentAsString();

		assertThat(html)
			// 원시 ID 단독 노출 제거: '역 ID'/'시설 ID' 라벨 대신 역·시설 이름(코드).
			.doesNotContain("역 ID")
			.doesNotContain("시설 ID")
			// 같은 시설 신고 목록에 다른 신고와 상세 크로스링크가 뜬다(현재 신고는 제외).
			.contains("같은 시설 신고")
			.contains("같은 시설 두 번째 신고")
			.contains("/admin/reports/%s/page".formatted(second));
	}

	@Test
	@DisplayName("관리자는 시설 상태 증거가 아닌 신고에서 override 링크를 보지 않는다")
	void adminReportDetailPageHidesOverrideLinkForNonFacilityStatusEvidence() throws Exception {
		String reportId = createReport("경로가 막힌 신고", "ROUTE_BLOCKED", "");

		String html = mockMvc.perform(get("/admin/reports/{reportId}/page", reportId)
				.with(httpBasic("admin-test", "admin-test-password")))
			.andExpect(status().isOk())
			.andReturn()
			.getResponse()
			.getContentAsString();

		assertThat(html)
			.contains("경로가 막힌 신고")
			.contains("경로가 막혔어요")
			.doesNotContain("manual override 요청")
			.doesNotContain("id=override-" + reportId);
	}

	@Test
	@DisplayName("관리자는 신고 번호 기준 endpoint로 신고 사진 thumbnail과 원본을 조회한다")
	void adminReportPhotoEndpointsLoadReportBoundPhotoAndWritePrivacyAudit() throws Exception {
		String reportId = createReportWithPhotoAndLocation("사진 endpoint로 확인할 신고");

		mockMvc.perform(get("/admin/reports/{reportId}/photo/thumbnail", reportId)
				.with(httpBasic("admin-test", "admin-test-password")))
			.andExpect(status().isOk())
			.andExpect(header().string("Cache-Control", "no-store, private"))
			.andExpect(header().string("Content-Type", "image/png"));

		mockMvc.perform(get("/admin/reports/{reportId}/photo/original", reportId)
				.with(httpBasic("admin-test", "admin-test-password")))
			.andExpect(status().isOk())
			.andExpect(header().string("Cache-Control", "no-store, private"))
			.andExpect(header().string("Content-Type", "image/png"));

		assertThat(auditEventRepository.findRecent(AdminAuditEventType.PRIVACY_READ, 2))
			.extracting(event -> event.action() + ":" + event.targetType() + ":" + event.targetId() + ":" + event.reason())
			.containsExactly(
				"VIEW_REPORT_PHOTO_ORIGINAL:FACILITY_REPORT_PHOTO:" + reportId + ":업무 맥락: 신고 원본 사진 조회",
				"VIEW_REPORT_PHOTO_THUMBNAIL:FACILITY_REPORT_PHOTO:" + reportId + ":업무 맥락: 신고 사진 미리보기 조회"
			);
	}

	@Test
	@DisplayName("신고 검수 권한만 있는 관리자는 신고 사진 endpoint에 접근할 수 없다")
	void adminReportPhotoEndpointsRequirePhotoReadPermission() throws Exception {
		String reportId = createReportWithPhotoAndLocation("사진 권한 경계를 확인할 신고");
		RequestPostProcessor reportReviewer = user("report-reviewer")
			.authorities(new SimpleGrantedAuthority(AdminPermission.REPORT_REVIEW.authority()));

		mockMvc.perform(get("/admin/reports/{reportId}/photo/thumbnail", reportId)
				.with(reportReviewer))
			.andExpect(status().isForbidden());

		mockMvc.perform(get("/admin/reports/{reportId}/photo/original", reportId)
				.with(reportReviewer))
			.andExpect(status().isForbidden());
	}

	@Test
	@DisplayName("사진 열람 권한이 있는 관리자만 목록에서 썸네일을 본다")
	void adminReportListShowsPhotoThumbnailOnlyWithReadPermission() throws Exception {
		String reportId = createReportWithPhotoAndLocation("썸네일로 확인할 신고");

		// 사진 열람 권한이 있는 계정(admin-test): 썸네일 img + 원본 링크 노출.
		String withPermission = mockMvc.perform(get("/admin/reports/page")
				.with(httpBasic("admin-test", "admin-test-password")))
			.andExpect(status().isOk())
			.andReturn().getResponse().getContentAsString();
		assertThat(withPermission)
			.contains("/admin/reports/%s/photo/thumbnail".formatted(reportId))
			.contains("/admin/reports/%s/photo/original".formatted(reportId));

		// 검수 권한만 있고 사진 열람 권한이 없는 계정: 썸네일 미노출, '있음' 텍스트만.
		RequestPostProcessor reportReviewer = user("report-reviewer")
			.authorities(new SimpleGrantedAuthority(AdminPermission.REPORT_REVIEW.authority()));
		String withoutPermission = mockMvc.perform(get("/admin/reports/page")
				.with(reportReviewer))
			.andExpect(status().isOk())
			.andReturn().getResponse().getContentAsString();
		assertThat(withoutPermission)
			.doesNotContain("/admin/reports/%s/photo/thumbnail".formatted(reportId))
			.contains("있음");
	}

	@Test
	@DisplayName("관리자 신고 사진 조회는 object key query endpoint를 열지 않는다")
	void adminReportPhotoQueryEndpointIsNotExposed() throws Exception {
		createReportWithPhotoAndLocation("object key 조회를 막을 신고");

		mockMvc.perform(get("/admin/reports/photos")
				.param("objectKey", "facility-reports/other-report/original.png")
				.with(httpBasic("admin-test", "admin-test-password")))
			.andExpect(status().isNotFound());
	}

	@Test
	@DisplayName("관리자는 상세 화면에서 승인한 뒤 상세 화면으로 돌아온다")
	void adminReportDetailPageReviewsReportAndRedirectsToDetail() throws Exception {
		String reportId = createReport("승인할 신고");

		mockMvc.perform(post("/admin/reports/{reportId}/page/review", reportId)
				.with(httpBasic("admin-test", "admin-test-password"))
				.with(csrf())
				.with(commandToken("/admin/reports/%s/page".formatted(reportId)))
				.contentType(MediaType.APPLICATION_FORM_URLENCODED)
				.param("decision", "ACCEPT"))
			.andExpect(status().is3xxRedirection())
			.andExpect(header().string("Location", "/admin/reports/%s/page".formatted(reportId)));

		String html = mockMvc.perform(get("/admin/reports/{reportId}/page", reportId)
				.with(httpBasic("admin-test", "admin-test-password")))
			.andExpect(status().isOk())
			.andReturn()
			.getResponse()
			.getContentAsString();

		assertThat(html)
			.contains("반영됨")
			.contains("admin-test");
	}

	@Test
	@DisplayName("신고 검수 폼은 같은 command token 재전송을 409로 차단한다")
	void reportReviewRejectsRepeatedCommandToken() throws Exception {
		String reportId = createReport("중복 제출을 막을 신고");
		MockHttpSession session = new MockHttpSession();
		String token = commandTokenFrom(getAdminHtml("/admin/reports/%s/page".formatted(reportId), session));

		mockMvc.perform(post("/admin/reports/{reportId}/page/review", reportId)
				.session(session)
				.with(httpBasic("admin-test", "admin-test-password"))
				.with(csrf())
				.contentType(MediaType.APPLICATION_FORM_URLENCODED)
				.param("commandToken", token)
				.param("decision", "ACCEPT"))
			.andExpect(status().is3xxRedirection());

		String conflictHtml = mockMvc.perform(post("/admin/reports/{reportId}/page/review", reportId)
				.session(session)
				.with(httpBasic("admin-test", "admin-test-password"))
				.with(csrf())
				.contentType(MediaType.APPLICATION_FORM_URLENCODED)
				.param("commandToken", token)
				.param("decision", "REJECT"))
			.andExpect(status().isConflict())
			.andReturn()
			.getResponse()
			.getContentAsString();

		String detailHtml = getAdminHtml("/admin/reports/%s/page".formatted(reportId), session);

		assertThat(conflictHtml)
			.contains("요청이 최신 상태와 충돌했습니다")
			.contains("이미 처리되었거나 만료된 관리자 요청입니다");
		assertThat(detailHtml)
			.contains("반영됨")
			.doesNotContain("반려됨");
		assertThat(auditEventRepository.findRecent(AdminAuditEventType.ADMIN_ACTION, 1))
			.singleElement()
			.satisfies(event -> {
				assertThat(event.outcome()).isEqualTo(AdminAuditOutcome.FAILURE);
				assertThat(event.action()).isEqualTo("POST /admin/reports/{reportId}/page/review");
			});
	}

	@Test
	@DisplayName("신고 검수 폼은 판정 누락 오류와 입력값을 상세 화면에 표시한다")
	void reportReviewValidationErrorRendersAdminHtml() throws Exception {
		String originalReportId = createReport("기준 신고");
		String reportId = createReport("판정 누락 신고");

		String html = mockMvc.perform(post("/admin/reports/{reportId}/page/review", reportId)
				.with(httpBasic("admin-test", "admin-test-password"))
				.with(csrf())
				.with(commandToken("/admin/reports/%s/page".formatted(reportId)))
				.contentType(MediaType.APPLICATION_FORM_URLENCODED)
				.param("duplicateOfReportId", originalReportId))
			.andExpect(status().isBadRequest())
			.andReturn()
			.getResponse()
			.getContentAsString();

		assertThat(html)
			.contains("신고 상세")
			.contains("입력값을 확인해 주세요")
			.contains("신고 판정 값을 선택해야 합니다.")
			.contains("판정 누락 신고")
			.contains("value=\"%s\"".formatted(originalReportId));
		assertThat(auditEventRepository.findRecent(AdminAuditEventType.PRIVACY_READ, 1))
			.singleElement()
			.satisfies(event -> {
				assertThat(event.actor()).isEqualTo("admin-test");
				assertThat(event.targetType()).isEqualTo("FACILITY_REPORT");
				assertThat(event.targetId()).isEqualTo(reportId);
				assertThat(event.action()).isEqualTo("VIEW_REPORT_DETAIL");
			});
	}

	@Test
	@DisplayName("관리자는 신고 상세 화면에서 검수 감사 이력을 확인한다")
	void adminReportDetailPageShowsReviewAuditHistory() throws Exception {
		String reportId = createReport("감사 이력을 확인할 신고");

		mockMvc.perform(post("/admin/reports/{reportId}/page/review", reportId)
				.with(httpBasic("admin-test", "admin-test-password"))
				.with(csrf())
				.with(commandToken("/admin/reports/%s/page".formatted(reportId)))
				.contentType(MediaType.APPLICATION_FORM_URLENCODED)
				.param("decision", "REJECT"))
			.andExpect(status().is3xxRedirection());

		String html = mockMvc.perform(get("/admin/reports/{reportId}/page", reportId)
				.with(httpBasic("admin-test", "admin-test-password")))
			.andExpect(status().isOk())
			.andReturn()
			.getResponse()
			.getContentAsString();

		assertThat(html)
			.contains("감사 이력")
			.contains("admin-test")
			.contains("반려")
			.contains("접수됨")
			.contains("반려됨");
	}

	@Test
	@DisplayName("관리자는 상세 화면에서 중복 처리 기준 신고를 확인한다")
	void adminReportDetailPageShowsDuplicateOriginalReport() throws Exception {
		String originalReportId = createReport("먼저 접수된 고장 신고");
		String duplicatedReportId = createReport("같은 시설에 대해 다시 들어온 신고");

		mockMvc.perform(post("/admin/reports/{reportId}/page/review", duplicatedReportId)
				.with(httpBasic("admin-test", "admin-test-password"))
				.with(csrf())
				.with(commandToken("/admin/reports/%s/page".formatted(duplicatedReportId)))
				.contentType(MediaType.APPLICATION_FORM_URLENCODED)
				.param("decision", "MARK_DUPLICATE")
				.param("duplicateOfReportId", originalReportId))
			.andExpect(status().is3xxRedirection())
			.andExpect(header().string("Location", "/admin/reports/%s/page".formatted(duplicatedReportId)));

		String html = mockMvc.perform(get("/admin/reports/{reportId}/page", duplicatedReportId)
				.with(httpBasic("admin-test", "admin-test-password")))
			.andExpect(status().isOk())
			.andReturn()
			.getResponse()
			.getContentAsString();

		assertThat(html)
			.contains("중복")
			.contains("기준 신고")
			.contains(originalReportId);
	}

	@Test
	@DisplayName("관리자 신고 화면은 관리자 인증을 요구한다")
	void adminReportPagesRequireAdminAuthentication() throws Exception {
		String reportId = createReport("인증 검증용 신고");

		mockMvc.perform(get("/admin/reports/page"))
			.andExpect(status().isUnauthorized());

		mockMvc.perform(get("/admin/reports/page")
				.with(httpBasic("basic-user", "user-test-password")))
			.andExpect(status().isForbidden());

		mockMvc.perform(get("/admin/reports/{reportId}/page", reportId))
			.andExpect(status().isUnauthorized());

		mockMvc.perform(get("/admin/reports/{reportId}/page", reportId)
				.with(httpBasic("basic-user", "user-test-password")))
			.andExpect(status().isForbidden());

		mockMvc.perform(get("/admin/reports/{reportId}/photo/thumbnail", reportId)
				.with(httpBasic("basic-user", "user-test-password")))
			.andExpect(status().isForbidden());

		mockMvc.perform(post("/admin/reports/{reportId}/page/review", reportId)
				.with(csrf())
				.contentType(MediaType.APPLICATION_FORM_URLENCODED)
				.param("decision", "ACCEPT"))
			.andExpect(status().isUnauthorized());

		mockMvc.perform(post("/admin/reports/{reportId}/page/review", reportId)
				.with(httpBasic("basic-user", "user-test-password"))
				.with(csrf())
				.contentType(MediaType.APPLICATION_FORM_URLENCODED)
				.param("decision", "ACCEPT"))
			.andExpect(status().isForbidden());
	}

	@Test
	@DisplayName("관리자는 선택한 신고들을 일괄 승인해 접수 대기열에서 뺀다")
	void bulkReviewAcceptsSelectedReports() throws Exception {
		String firstReportId = createReport("일괄 승인 신고 1");
		String secondReportId = createReport("일괄 승인 신고 2");

		mockMvc.perform(post("/admin/reports/bulk-review")
				.with(httpBasic("admin-test", "admin-test-password"))
				.with(csrf())
				.with(commandToken("/admin/reports/%s/page".formatted(firstReportId)))
				.contentType(MediaType.APPLICATION_FORM_URLENCODED)
				.param("reportIds", firstReportId, secondReportId)
				.param("decision", "ACCEPT"))
			.andExpect(status().is3xxRedirection())
			.andExpect(header().string("Location", "/admin/reports/page"));

		String submittedHtml = mockMvc.perform(get("/admin/reports/page")
				.param("status", "SUBMITTED")
				.with(httpBasic("admin-test", "admin-test-password")))
			.andExpect(status().isOk())
			.andReturn()
			.getResponse()
			.getContentAsString();

		assertThat(submittedHtml)
			.doesNotContain("일괄 승인 신고 1")
			.doesNotContain("일괄 승인 신고 2");
	}

	@Test
	@DisplayName("일괄 검수는 선택이 없으면 안내만 하고 목록으로 돌아온다")
	void bulkReviewWithoutSelectionReturnsToList() throws Exception {
		mockMvc.perform(post("/admin/reports/bulk-review")
				.with(httpBasic("admin-test", "admin-test-password"))
				.with(csrf())
				.with(commandToken("/admin/reports/page"))
				.contentType(MediaType.APPLICATION_FORM_URLENCODED)
				.param("decision", "ACCEPT"))
			.andExpect(status().is3xxRedirection())
			.andExpect(header().string("Location", "/admin/reports/page"));
	}

	@Test
	@DisplayName("일괄 검수는 returnTo 필터 컨텍스트로 되돌아가고 외부 URL은 목록으로 막는다")
	void bulkReviewHonorsSafeReturnTo() throws Exception {
		String reportId = createReport("returnTo 유지 신고");

		mockMvc.perform(post("/admin/reports/bulk-review")
				.with(httpBasic("admin-test", "admin-test-password"))
				.with(csrf())
				.with(commandToken("/admin/reports/%s/page".formatted(reportId)))
				.contentType(MediaType.APPLICATION_FORM_URLENCODED)
				.param("reportIds", reportId)
				.param("decision", "ACCEPT")
				.param("returnTo", "/admin/reports/page?status=SUBMITTED&keyword=x"))
			.andExpect(status().is3xxRedirection())
			.andExpect(header().string("Location", "/admin/reports/page?status=SUBMITTED&keyword=x"));

		String otherReportId = createReport("open redirect 차단 신고");
		mockMvc.perform(post("/admin/reports/bulk-review")
				.with(httpBasic("admin-test", "admin-test-password"))
				.with(csrf())
				.with(commandToken("/admin/reports/%s/page".formatted(otherReportId)))
				.contentType(MediaType.APPLICATION_FORM_URLENCODED)
				.param("reportIds", otherReportId)
				.param("decision", "ACCEPT")
				.param("returnTo", "https://evil.example.com/admin/reports/page"))
			.andExpect(status().is3xxRedirection())
			.andExpect(header().string("Location", "/admin/reports/page"));
	}

	private String createReport(String description) throws Exception {
		return createReport(description, "BROKEN", "");
	}

	private String getAdminHtml(String path, MockHttpSession session) throws Exception {
		return mockMvc.perform(get(path)
				.session(session)
				.with(httpBasic("admin-test", "admin-test-password")))
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

	private String createReportWithPhotoAndLocation(String description) throws Exception {
		return createReport(
			description,
			"BROKEN",
			"""
				,
						  "photoFileName": "elevator-notice.png",
						  "photoContentType": "image/png",
						  "photoDataBase64": "%s",
						  "latitude": 37.302421,
						  "longitude": 126.866221
					"""
					.formatted(VALID_PNG_BASE64)
			);
	}

	private String createReport(String description, String optionalJson) throws Exception {
		return createReport(description, "BROKEN", optionalJson);
	}

	private String createReport(String description, String reportType, String optionalJson) throws Exception {
		String response = mockMvc.perform(post("/api/v1/reports")
				.with(httpBasic("basic-user", "user-test-password"))
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					  {
					    "stationId": "station-sangnoksu",
					    "facilityId": "facility-sangnoksu-elevator-1",
					    "reportType": "%s",
					    "description": "%s"%s
					  }
					""".formatted(reportType, description, optionalJson)))
			.andExpect(status().isCreated())
			.andReturn()
			.getResponse()
			.getContentAsString();
		return JsonPath.read(response, "$.data.id");
	}
}
