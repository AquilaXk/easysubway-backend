package com.easysubway.ads.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.easysubway.admin.audit.adapter.out.persistence.InMemoryAdminAuditEventRepository;
import com.easysubway.admin.audit.domain.AdminAuditEvent;
import com.easysubway.admin.audit.domain.AdminAuditEventType;
import com.easysubway.ads.application.port.out.AdRepository;
import com.easysubway.ads.domain.AdCreative;
import com.easysubway.common.error.InvalidRequestException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

@SpringBootTest(properties = "easysubway.ads.asset-origin=https://assets.easysubway.example")
@AutoConfigureMockMvc
@DisplayName("관리자 광고 소재 화면")
class AdminAdsPageControllerTest {

	private static final LocalDateTime T0 = LocalDateTime.parse("2026-07-11T00:00:00");

	@Autowired
	private MockMvc mockMvc;
	@Autowired
	private JdbcTemplate jdbcTemplate;
	@Autowired
	private AdRepository repository;
	@Autowired
	private InMemoryAdminAuditEventRepository auditEventRepository;

	@BeforeEach
	void setUp() {
		jdbcTemplate.update("DELETE FROM ad_event_daily");
		jdbcTemplate.update("DELETE FROM ad_creatives");
		jdbcTemplate.update("""
			MERGE INTO ad_placements (id, display_name, enabled) KEY(id) VALUES
			('route-result-bottom', '경로 결과 하단', TRUE),
			('station-detail-bottom', '역 상세 하단', TRUE)
			""");
	}

	@Test
	@DisplayName("운영 권한만 광고 화면에 접근하고 navigation에서 광고 메뉴를 본다")
	void adsPageRequiresOperationsManage() throws Exception {
		RequestPostProcessor viewer = user("viewer")
			.authorities(new SimpleGrantedAuthority("admin.view"));
		PreAuthorize permission = AdminAdsPageController.class.getAnnotation(PreAuthorize.class);
		assertThat(permission).isNotNull();
		assertThat(permission.value())
			.isEqualTo("hasAuthority('admin.operations.manage')");

		mockMvc.perform(get("/admin/ads/page").with(viewer))
			.andExpect(status().isForbidden());
		mockMvc.perform(post("/admin/ads")
				.with(csrf())
				.with(viewer)
				.contentType(MediaType.APPLICATION_FORM_URLENCODED))
			.andExpect(status().isForbidden());
		String viewerDashboard = mockMvc.perform(get("/admin/dashboard/page").with(viewer))
			.andExpect(status().isOk())
			.andReturn().getResponse().getContentAsString();
		assertThat(viewerDashboard).doesNotContain("/admin/ads/page");

		String operationsPage = getAdsHtml(new MockHttpSession());
		assertThat(operationsPage).contains("href=\"/admin/ads/page\"");
	}

	@Test
	@DisplayName("목록과 단일 upsert form은 접근 가능한 labels와 보호 토큰을 렌더링한다")
	void rendersCreativeListAndProtectedForms() throws Exception {
		repository.save(creative("disabled-ad", "route-result-bottom", "비활성 광고주", false));
		repository.save(creative("enabled-ad", "station-detail-bottom", "활성 광고주", true));

		String html = getAdsHtml(new MockHttpSession());

		assertThat(html)
			.contains("<h1>광고 소재</h1>")
			.contains("for=\"creative-id\"")
			.contains("pattern=\"(?!\\.{1,2}$)[A-Za-z0-9._\\-]{1,64}\"")
			.contains("for=\"placement-id\"")
			.contains("for=\"advertiser-name\"")
			.contains("for=\"image-url\"")
			.contains("for=\"landing-url\"")
			.contains("for=\"alt-text\"")
			.contains("for=\"starts-at\"")
			.contains("for=\"ends-at\"")
			.contains("scope=\"col\"")
			.contains("비활성 광고주")
			.contains("활성 광고주")
			.contains("<th scope=\"row\">disabled-ad</th>")
			.contains("aria-label=\"disabled-ad 비활성 광고주 이미지 열기\"")
			.contains("aria-label=\"disabled-ad 비활성 광고주 랜딩 페이지 열기\"")
			.contains("/admin/ads/disabled-ad/enable")
			.contains("/admin/ads/enabled-ad/disable")
			.doesNotContain("event_count", "/api/ads/events", "노출 수", "클릭 수");
		assertThat(count(html, "name=\"commandToken\"")).isEqualTo(3);
		assertThat(count(html, "name=\"_csrf\"")).isGreaterThanOrEqualTo(3);
	}

	@Test
	@DisplayName("valid upsert는 UTC instant를 저장하고 PRG와 ADMIN_ACTION audit을 남긴다")
	void upsertsCreativeAndAudits() throws Exception {
		MockHttpSession session = new MockHttpSession();

		postCreative(session, "광고주", "  최초 등록  ")
			.andExpect(status().is3xxRedirection())
			.andExpect(redirectedUrl("/admin/ads/page"));
		AdCreative created = repository.findById("creative-1").orElseThrow();
		assertThat(created.advertiserName()).isEqualTo("광고주");
		assertThat(created.startsAt()).isEqualTo(T0);
		assertThat(created.enabled()).isFalse();
		assertThat(auditRecorded("CREATE_AD_CREATIVE", "creative-1", "최초 등록")).isTrue();

		postCreative(session, "수정 광고주", "문구 수정")
			.andExpect(status().is3xxRedirection())
			.andExpect(redirectedUrl("/admin/ads/page"));
		assertThat(repository.findById("creative-1").orElseThrow().advertiserName()).isEqualTo("수정 광고주");
		assertThat(auditRecorded("UPDATE_AD_CREATIVE", "creative-1", "문구 수정")).isTrue();
	}

	@Test
	@DisplayName("enable과 disable은 상태를 바꾸고 각각 audit을 남긴다")
	void enablesAndDisablesCreativeWithAudit() throws Exception {
		repository.save(creative("creative-1", "route-result-bottom", "광고주", false));
		MockHttpSession session = new MockHttpSession();

		postState(session, "enable", "캠페인 시작")
			.andExpect(status().is3xxRedirection())
			.andExpect(redirectedUrl("/admin/ads/page"));
		assertThat(repository.findById("creative-1").orElseThrow().enabled()).isTrue();
		assertThat(auditRecorded("ENABLE_AD_CREATIVE", "creative-1", "캠페인 시작")).isTrue();

		postState(session, "disable", "캠페인 종료")
			.andExpect(status().is3xxRedirection())
			.andExpect(redirectedUrl("/admin/ads/page"));
		assertThat(repository.findById("creative-1").orElseThrow().enabled()).isFalse();
		assertThat(auditRecorded("DISABLE_AD_CREATIVE", "creative-1", "캠페인 종료")).isTrue();
	}

	@Test
	@DisplayName("누락되거나 잘못된 instant는 400이고 CSRF와 command token 없는 POST는 거부한다")
	void rejectsInvalidInstantAndUnprotectedPosts() throws Exception {
		MockHttpSession session = new MockHttpSession();
		mockMvc.perform(post("/admin/ads")
				.session(session)
				.with(csrf())
				.with(commandToken(session))
				.with(operationsManager())
				.contentType(MediaType.APPLICATION_FORM_URLENCODED)
				.param("id", "missing-time")
				.param("placementId", "route-result-bottom")
				.param("advertiserName", "광고주")
				.param("imageUrl", "https://assets.easysubway.example/ads/missing.png")
				.param("landingUrl", "https://partner.example/missing")
				.param("altText", "광고 대체텍스트")
				.param("endsAt", "")
				.param("reason", "누락 시각 검증"))
			.andExpect(status().isBadRequest());
		assertThat(repository.findById("missing-time")).isEmpty();

		mockMvc.perform(post("/admin/ads")
				.session(session)
				.with(csrf())
				.with(commandToken(session))
				.with(operationsManager())
				.contentType(MediaType.APPLICATION_FORM_URLENCODED)
				.param("id", "invalid-time")
				.param("placementId", "route-result-bottom")
				.param("advertiserName", "광고주")
				.param("imageUrl", "https://assets.easysubway.example/ads/invalid.png")
				.param("landingUrl", "https://partner.example/invalid")
				.param("altText", "광고 대체텍스트")
				.param("startsAt", "not-an-instant")
				.param("endsAt", "")
				.param("reason", "잘못된 시각 검증"))
			.andExpect(status().isBadRequest());
		assertThat(repository.findById("invalid-time")).isEmpty();
		assertThatThrownBy(() -> new AdminAdsPageController.AdForm(
			"missing-time",
			"route-result-bottom",
			"광고주",
			"https://assets.easysubway.example/ads/missing.png",
			"https://partner.example/missing",
			"광고 대체텍스트",
			null,
			null,
			"누락 시각 검증"
		).toCreative())
			.isInstanceOf(InvalidRequestException.class)
			.hasMessage("startsAt 형식이 올바르지 않습니다.")
			.hasNoCause();

		mockMvc.perform(post("/admin/ads/creative-1/enable")
				.with(operationsManager())
				.contentType(MediaType.APPLICATION_FORM_URLENCODED)
				.param("reason", "보호 누락"))
			.andExpect(status().isForbidden());

		mockMvc.perform(post("/admin/ads/creative-1/enable")
				.with(csrf())
				.with(operationsManager())
				.contentType(MediaType.APPLICATION_FORM_URLENCODED)
				.param("reason", "token 누락"))
			.andExpect(status().isConflict());
	}

	@Test
	@DisplayName("blank 또는 500자 초과 reason은 mutation과 audit 전에 400으로 거부한다")
	void rejectsInvalidReasonsBeforeMutationAndAudit() throws Exception {
		MockHttpSession session = new MockHttpSession();
		for (String reason : List.of(" ", "x".repeat(501))) {
			postCreative(session, "invalid-reason", "광고주", reason)
				.andExpect(status().isBadRequest());
		}

		assertThat(repository.findById("invalid-reason")).isEmpty();
		assertThat(auditRecorded("CREATE_AD_CREATIVE", "invalid-reason", null)).isFalse();
		assertThat(auditRecorded("UPDATE_AD_CREATIVE", "invalid-reason", null)).isFalse();
	}

	@Test
	@DisplayName("admin create·enable 소재는 public ETag 응답 후 disable 시 204로 사라진다")
	void adminLifecycleControlsPublicCreative() throws Exception {
		MockHttpSession session = new MockHttpSession();
		String id = "route.A_1-";
		postCreative(
			session, id, "광고주", "공개 광고 등록",
			"2000-01-01T00:00:00Z", "")
			.andExpect(status().is3xxRedirection());
		AdCreative created = repository.findById(id).orElseThrow();
		assertThat(created.startsAt()).isEqualTo(LocalDateTime.parse("2000-01-01T00:00:00"));
		assertThat(created.endsAt()).isNull();
		String enableAction = "/admin/ads/" + id + "/enable";
		assertThat(getAdsHtml(session)).contains("action=\"" + enableAction + "\"");
		postState(session, enableAction, "공개 시작")
			.andExpect(status().is3xxRedirection());

		mockMvc.perform(get("/api/ads/active").param("placement", "route-result-bottom"))
			.andExpect(status().isOk())
			.andExpect(header().exists("ETag"));

		postState(session, "/admin/ads/" + id + "/disable", "공개 종료")
			.andExpect(status().is3xxRedirection());
		mockMvc.perform(get("/api/ads/active").param("placement", "route-result-bottom"))
			.andExpect(status().isNoContent());
	}

	private org.springframework.test.web.servlet.ResultActions postCreative(
		MockHttpSession session,
		String advertiserName,
		String reason
	) throws Exception {
		return postCreative(session, "creative-1", advertiserName, reason);
	}

	private org.springframework.test.web.servlet.ResultActions postCreative(
		MockHttpSession session,
		String id,
		String advertiserName,
		String reason
	) throws Exception {
		return postCreative(
			session, id, advertiserName, reason,
			"2026-07-11T00:00:00Z", "2026-07-12T00:00:00Z");
	}

	private org.springframework.test.web.servlet.ResultActions postCreative(
		MockHttpSession session,
		String id,
		String advertiserName,
		String reason,
		String startsAt,
		String endsAt
	) throws Exception {
		return mockMvc.perform(post("/admin/ads")
			.session(session)
			.with(csrf())
			.with(commandToken(session))
			.with(operationsManager())
			.contentType(MediaType.APPLICATION_FORM_URLENCODED)
			.param("id", id)
			.param("placementId", "route-result-bottom")
			.param("advertiserName", advertiserName)
			.param("imageUrl", "https://assets.easysubway.example/ads/" + id + ".png")
			.param("landingUrl", "https://partner.example/" + id)
			.param("altText", "광고 대체텍스트")
			.param("startsAt", startsAt)
			.param("endsAt", endsAt)
			.param("reason", reason));
	}

	private org.springframework.test.web.servlet.ResultActions postState(
		MockHttpSession session,
		String action,
		String reason
	) throws Exception {
		String path = action.startsWith("/") ? action : "/admin/ads/creative-1/" + action;
		return mockMvc.perform(post(path)
			.session(session)
			.with(csrf())
			.with(commandToken(session))
			.with(operationsManager())
			.contentType(MediaType.APPLICATION_FORM_URLENCODED)
			.param("reason", reason));
	}

	private AdCreative creative(String id, String placementId, String advertiser, boolean enabled) {
		return new AdCreative(
			id,
			placementId,
			"https://assets.easysubway.example/ads/" + id + ".png",
			"https://partner.example/" + id,
			advertiser,
			advertiser + " 대체텍스트",
			T0,
			T0.plusDays(1),
			enabled);
	}

	private boolean auditRecorded(String action, String targetId, String reason) {
		List<AdminAuditEvent> events = auditEventRepository.findRecent(AdminAuditEventType.ADMIN_ACTION, 100);
		return events.stream().anyMatch(event ->
			"AD_CREATIVE".equals(event.targetType())
				&& action.equals(event.action())
				&& targetId.equals(event.targetId())
				&& "operator".equals(event.actor())
				&& "admin.operations.manage".equals(event.rolePermission())
				&& com.easysubway.admin.audit.domain.AdminAuditOutcome.SUCCESS == event.outcome()
				&& (reason == null || reason.equals(event.reason())));
	}

	private RequestPostProcessor operationsManager() {
		return user("operator").authorities(new SimpleGrantedAuthority("admin.operations.manage"));
	}

	private RequestPostProcessor commandToken(MockHttpSession session) {
		return request -> {
			request.addParameter("commandToken", commandTokenFrom(getAdsHtml(session)));
			return request;
		};
	}

	private String getAdsHtml(MockHttpSession session) {
		try {
			return mockMvc.perform(get("/admin/ads/page")
					.session(session)
					.with(operationsManager()))
				.andExpect(status().isOk())
				.andReturn().getResponse().getContentAsString();
		} catch (Exception exception) {
			throw new IllegalStateException(exception);
		}
	}

	private static String commandTokenFrom(String html) {
		var matcher = Pattern.compile("name=\"commandToken\" value=\"([^\"]+)\"").matcher(html);
		if (!matcher.find()) {
			throw new IllegalStateException("commandToken missing");
		}
		return matcher.group(1);
	}

	private static int count(String value, String needle) {
		return (value.length() - value.replace(needle, "").length()) / needle.length();
	}
}
