package com.easysubway.common.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.easysubway.admin.authorization.AdminPermission;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.server.PathContainer;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;

/**
 * SecurityConfig의 모든 URL matcher 경계를 실제 Tomcat 위에서 고정한다(#526).
 * Spring Security의 matcher 구현을 바꿔도(AntPathRequestMatcher → PathPatternRequestMatcher) 허용·거부
 * 결과가 달라지지 않아야 하므로, 컨테이너가 pathInfo를 디코딩하고 StrictHttpFirewall이 요청을 거르는
 * 실제 경로 처리까지 포함해 검증한다. 모든 행은 구현 교체 전에 통과한 기대값이다.
 */
@DisplayName("보안 설정 경로 경계")
class SecurityConfigPathBoundaryTest extends SecurityPathBoundarySupport {

	// ---------------------------------------------------------------- admin 권한 규칙

	/** (method, 구체 경로, 필요한 권한 중 하나). hasAnyAuthority 규칙은 권한마다 한 행이 아니라 목록으로 둔다. */
	private record AdminRule(String method, String path, List<AdminPermission> anyOf) {

		static AdminRule of(String method, String path, AdminPermission... anyOf) {
			return new AdminRule(method, path, List.of(anyOf));
		}
	}

	/** 데이터팩 검토 POST 그룹(alias·quarantine·evidence·override·candidates)이 공유하는 hasAnyAuthority 목록. */
	private static final AdminPermission[] DATAPACK_REVIEW_PERMISSIONS = {
		AdminPermission.DATAPACK_ALIAS_REVIEW,
		AdminPermission.DATAPACK_QUARANTINE_REVIEW,
		AdminPermission.DATAPACK_EVIDENCE_REVIEW,
		AdminPermission.DATAPACK_OVERRIDE_REQUEST,
		AdminPermission.DATAPACK_OVERRIDE_APPROVE,
		AdminPermission.DATAPACK_CANDIDATE_BUILD
	};

	private static final List<AdminRule> ADMIN_RULES = List.of(
		AdminRule.of("POST", "/admin/reports/1/review", AdminPermission.REPORT_REVIEW),
		AdminRule.of("GET", "/admin/reports/1/photo/thumbnail", AdminPermission.REPORT_PHOTO_READ),
		AdminRule.of("GET", "/admin/reports/1/photo/original", AdminPermission.REPORT_PHOTO_READ),
		AdminRule.of("GET", "/admin/reports/1", AdminPermission.REPORT_REVIEW),
		AdminRule.of("GET", "/admin/facilities/editor/page", AdminPermission.MASTER_EDIT),
		AdminRule.of("GET", "/admin/stations/1/layouts/page", AdminPermission.MASTER_EDIT),
		AdminRule.of("GET", "/admin/stations/1/layout-sources", AdminPermission.MASTER_EDIT),
		AdminRule.of("GET", "/admin/stations/1/layouts", AdminPermission.MASTER_EDIT),
		AdminRule.of("GET", "/admin/stations/1/route-nodes", AdminPermission.MASTER_EDIT),
		AdminRule.of("GET", "/admin/stations/1/route-edges", AdminPermission.MASTER_EDIT),
		AdminRule.of("POST", "/admin/facilities/1", AdminPermission.MASTER_EDIT),
		AdminRule.of("PUT", "/admin/facilities/1", AdminPermission.MASTER_EDIT),
		AdminRule.of("PATCH", "/admin/facilities/1", AdminPermission.MASTER_EDIT),
		AdminRule.of("POST", "/admin/stations/1", AdminPermission.MASTER_EDIT),
		AdminRule.of("PUT", "/admin/stations/1", AdminPermission.MASTER_EDIT),
		AdminRule.of("PATCH", "/admin/stations/1", AdminPermission.MASTER_EDIT),
		AdminRule.of("POST", "/admin/field-verifications/1", AdminPermission.FIELD_OPERATE),
		AdminRule.of("PATCH", "/admin/field-verifications/1", AdminPermission.FIELD_OPERATE),
		AdminRule.of("GET", "/admin/field-verifications/1", AdminPermission.FIELD_OPERATE),
		AdminRule.of("POST", "/admin/data-collections/1", AdminPermission.DATA_OPERATE),
		AdminRule.of("POST", "/admin/data-sources/1", AdminPermission.DATA_OPERATE),
		AdminRule.of("POST", "/admin/notifications/1", AdminPermission.DATA_OPERATE),
		AdminRule.of("GET", "/admin/data-collections/1", AdminPermission.DATA_OPERATE),
		AdminRule.of("GET", "/admin/data-sources/1", AdminPermission.DATA_OPERATE),
		AdminRule.of("GET", "/admin/notifications/1", AdminPermission.DATA_OPERATE),
		AdminRule.of("POST", "/admin/batches/1/run", AdminPermission.BATCH_RUN),
		AdminRule.of("POST", "/admin/batches/1/retry", AdminPermission.BATCH_RETRY),
		AdminRule.of("GET", "/admin/batches/1", AdminPermission.DATA_OPERATE),
		AdminRule.of("GET", "/admin/datapack/candidates/page", AdminPermission.DATAPACK_READ),
		AdminRule.of("POST", "/admin/datapack/source-snapshots", AdminPermission.DATAPACK_SOURCE_RUN),
		AdminRule.of("POST", "/admin/datapack/source-snapshots/1/run", AdminPermission.DATAPACK_SOURCE_RUN),
		AdminRule.of("POST", "/admin/datapack/alias-approvals/1", DATAPACK_REVIEW_PERMISSIONS),
		AdminRule.of("POST", "/admin/datapack/quarantine-records/1", DATAPACK_REVIEW_PERMISSIONS),
		AdminRule.of("POST", "/admin/datapack/facility-evidence/1", DATAPACK_REVIEW_PERMISSIONS),
		AdminRule.of("POST", "/admin/datapack/manual-overrides/1", DATAPACK_REVIEW_PERMISSIONS),
		AdminRule.of("POST", "/admin/datapack/candidates/1", DATAPACK_REVIEW_PERMISSIONS),
		AdminRule.of("POST", "/admin/datapack/release-requests", AdminPermission.DATAPACK_STAGING_PROMOTE),
		AdminRule.of("POST", "/admin/datapack/release-requests/1/approve",
			AdminPermission.DATAPACK_PRODUCTION_APPROVE),
		AdminRule.of("POST", "/admin/datapack/release-deliveries/1/repair",
			AdminPermission.DATAPACK_PRODUCTION_APPROVE),
		AdminRule.of("POST", "/admin/datapack/release-channels/1",
			AdminPermission.DATAPACK_STAGING_PROMOTE, AdminPermission.DATAPACK_PRODUCTION_APPROVE,
			AdminPermission.DATAPACK_ROLLBACK),
		AdminRule.of("GET", "/admin/codes/1", AdminPermission.OPERATIONS_MANAGE),
		AdminRule.of("POST", "/admin/incidents/1", AdminPermission.OPERATIONS_MANAGE),
		AdminRule.of("GET", "/admin/notices/1", AdminPermission.OPERATIONS_MANAGE),
		AdminRule.of("GET", "/admin/ads/1", AdminPermission.OPERATIONS_MANAGE),
		AdminRule.of("GET", "/admin/audits/privacy/1", AdminPermission.PRIVACY_LOG_READ),
		AdminRule.of("GET", "/admin/audits/1", AdminPermission.AUDIT_READ),
		AdminRule.of("GET", "/admin/api/errors", AdminPermission.ERROR_READ),
		AdminRule.of("GET", "/admin/api/errors/1", AdminPermission.ERROR_READ),
		AdminRule.of("GET", "/admin/errors/1", AdminPermission.ERROR_READ),
		AdminRule.of("GET", "/admin/system/1", AdminPermission.SECURITY_AUDIT),
		AdminRule.of("GET", "/admin/usage/1", AdminPermission.SECURITY_AUDIT),
		AdminRule.of("GET", "/admin/dashboard/page", AdminPermission.ADMIN_VIEW)
	);

	static Stream<Arguments> adminRuleCases() {
		List<Case> cases = new ArrayList<>();
		for (AdminRule rule : ADMIN_RULES) {
			for (AdminPermission permission : rule.anyOf()) {
				cases.add(Case.of(permissionCaller(permission), rule.method(), rule.path(), 200));
			}
			cases.add(Case.of(SUPER_ADMIN, rule.method(), rule.path(), 200));
			cases.add(Case.of(ANONYMOUS, rule.method(), rule.path(), 401));
			cases.add(Case.of(OPERATOR, rule.method(), rule.path(), 403));
			if (!rule.anyOf().contains(AdminPermission.ADMIN_VIEW)) {
				cases.add(Case.of(permissionCaller(AdminPermission.ADMIN_VIEW), rule.method(), rule.path(), 403));
			}
		}
		// 비슷한 권한이어도 다른 permission으로는 열리지 않는다.
		cases.add(Case.of(permissionCaller(AdminPermission.REPORT_REVIEW), "GET", "/admin/reports/1/photo/original", 403));
		cases.add(Case.of(permissionCaller(AdminPermission.REPORT_PHOTO_READ), "GET", "/admin/reports/1", 403));
		cases.add(Case.of(permissionCaller(AdminPermission.BATCH_RETRY), "POST", "/admin/batches/1/run", 403));
		cases.add(Case.of(permissionCaller(AdminPermission.BATCH_RUN), "POST", "/admin/batches/1/retry", 403));
		cases.add(Case.of(permissionCaller(AdminPermission.AUDIT_READ), "GET", "/admin/audits/privacy/1", 403));
		cases.add(Case.of(permissionCaller(AdminPermission.PRIVACY_LOG_READ), "GET", "/admin/audits/1", 403));
		cases.add(Case.of(permissionCaller(AdminPermission.DATAPACK_STAGING_PROMOTE),
			"POST", "/admin/datapack/release-requests/1/approve", 403));
		return cases.stream().map(Arguments::of);
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("adminRuleCases")
	@DisplayName("admin 체인은 규칙별 permission만 허용하고 나머지는 거부한다")
	void adminRulesAllowOnlyRequiredPermission(Case testCase) throws Exception {
		assertOutcome(testCase);
	}

	// ---------------------------------------------------------------- admin 체인 선택·진입점

	static Stream<Arguments> adminChainBoundaryCases() {
		return Stream.of(
			// 체인 경계: /admin 하위(끝 슬래시·인코딩된 영문자 포함)는 admin 체인이 처리해 401 진입점을 쓴다.
			Case.of(ANONYMOUS, "GET", "/admin", 401),
			Case.of(ANONYMOUS, "GET", "/admin/", 401),
			Case.of(ANONYMOUS, "GET", "/admin/dashboard/page", 401),
			Case.of(ANONYMOUS, "GET", "/admin/dashboard/page/", 401),
			Case.of(ANONYMOUS, "GET", "/admin/reports/1/photo/original/", 401),
			Case.of(ANONYMOUS, "GET", "/admin/dashboard/page?next=/admin/login", 401),
			Case.of(ANONYMOUS, "GET", "/%61dmin/dashboard/page", 401),
			Case.of(ANONYMOUS, "GET", "/admin/%64ashboard/page", 401),
			Case.of(ANONYMOUS, "GET", "/admin/dashboard/%70age", 401),
			Case.of(ANONYMOUS, "GET", "/admin/api/errors.json", 401),
			Case.of(ANONYMOUS, "POST", "/admin/dashboard/page", 401),
			Case.of(ANONYMOUS, "POST", "/admin/dashboard/page", 403).withoutCsrfToken(),
			Case.of(SUPER_ADMIN, "POST", "/admin/reports/1/review", 403).withoutCsrfToken(),
			// 대소문자가 다른 접두사는 admin 체인이 아니라 기본 차단 체인으로 가서 누구도 열 수 없다.
			Case.of(ANONYMOUS, "GET", "/ADMIN/dashboard/page", 403),
			Case.of(ANONYMOUS, "GET", "/Admin/dashboard/page", 403),
			Case.of(SUPER_ADMIN, "GET", "/ADMIN/dashboard/page", 403),
			Case.of(SUPER_ADMIN, "GET", "/Admin/reports/1", 403),
			Case.of(SUPER_ADMIN, "POST", "/ADMIN/reports/1/review", 403),
			Case.of(SUPER_ADMIN, "GET", "/admin-x/dashboard/page", 403),
			Case.of(SUPER_ADMIN, "GET", "/adminx", 403),
			// 이중 슬래시·경로 매개변수·인코딩된 점/슬래시/퍼센트는 방화벽이 matcher 이전에 거부한다.
			Case.of(SUPER_ADMIN, "GET", "/admin//dashboard/page", 400),
			Case.of(SUPER_ADMIN, "GET", "//admin/dashboard/page", 400),
			Case.of(SUPER_ADMIN, "GET", "/admin/dashboard;x=1/page", 400),
			Case.of(SUPER_ADMIN, "GET", "/admin;x=1/dashboard/page", 400),
			Case.of(SUPER_ADMIN, "GET", "/admin/%2e%2e/operator/x", 400),
			Case.of(SUPER_ADMIN, "GET", "/admin/dashboard%2fpage", 400),
			Case.of(SUPER_ADMIN, "GET", "/admin/dashboard%252fpage", 400),
			Case.of(SUPER_ADMIN, "GET", "/admin/dashboard%5cpage", 400),
			Case.of(SUPER_ADMIN, "GET", "/admin/dashboard/%00", 400),
			// 진입점 선택: Accept 와 HX-Request 헤더에 따라 응답 방식이 갈리고, 그 밖에는 401이다.
			Case.of(ANONYMOUS, "GET", "/admin/dashboard/page", 302).accept("text/html").redirectsTo("/admin/login"),
			Case.of(ANONYMOUS, "GET", "/admin", 302).accept("text/html").redirectsTo("/admin/login"),
			Case.of(ANONYMOUS, "GET", "/admin/dashboard/page", 204).header("HX-Request", "true"),
			Case.of(ANONYMOUS, "GET", "/admin/dashboard/page", 401).accept("application/json"),
			Case.of(ANONYMOUS, "GET", "/admin/dashboard/page", 401).accept("*/*"),
			// 로그인 페이지 두 곳만 공개이고 같은 경로의 변형은 공개되지 않는다.
			Case.of(ANONYMOUS, "GET", "/admin/login", 200),
			Case.of(ANONYMOUS, "GET", "/admin/error/page", 200),
			Case.of(ANONYMOUS, "GET", "/admin/Login", 401),
			Case.of(ANONYMOUS, "GET", "/admin/login/x", 401),
			Case.of(ANONYMOUS, "GET", "/admin/error/page/x", 401),
			// 잘못된 자격 증명은 권한과 무관하게 401이다.
			Case.of(Caller.basic("perm-unknown"), "GET", "/admin/dashboard/page", 401),
			Case.of(new Caller("wrong-password", basicHeader("boundary-super", "wrong")),
				"GET", "/admin/dashboard/page", 401)
		).map(Arguments::of);
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("adminChainBoundaryCases")
	@DisplayName("admin 체인 선택과 인증 진입점은 경로 변형에도 같은 결과를 낸다")
	void adminChainBoundary(Case testCase) throws Exception {
		assertOutcome(testCase);
	}

	// ---------------------------------------------------------------- operator 체인

	static Stream<Arguments> operatorChainCases() {
		return Stream.of(
			// 허용: operator 역할만. 전역 관리자·일반 사용자·익명은 거부한다.
			Case.of(OPERATOR, "GET", "/operator/accessibility-report/page", 200),
			Case.of(OPERATOR, "GET", "/operator", 200),
			Case.of(OPERATOR, "GET", "/operator/api/reports", 200),
			Case.of(SUPER_ADMIN, "GET", "/operator/accessibility-report/page", 403),
			Case.of(PLAIN_USER, "GET", "/operator/accessibility-report/page", 403),
			Case.of(OPERATOR, "GET", "/admin/dashboard/page", 403),
			Case.of(ANONYMOUS, "GET", "/operator/login", 200),
			// 진입점: /operator/api/** 는 401, 그 밖의 /operator/** 는 로그인 페이지로 이동한다.
			Case.of(ANONYMOUS, "GET", "/operator/api/reports", 401),
			Case.of(ANONYMOUS, "GET", "/operator/api", 401),
			Case.of(ANONYMOUS, "GET", "/operator/api/", 401),
			Case.of(ANONYMOUS, "GET", "/operator/api/reports/", 401),
			Case.of(ANONYMOUS, "GET", "/operator/api/reports?x=1", 401),
			Case.of(ANONYMOUS, "GET", "/operator/%61pi/reports", 401),
			Case.of(ANONYMOUS, "GET", "/operator/api/%72eports", 401),
			Case.of(ANONYMOUS, "GET", "/%6fperator/api/reports", 401),
			Case.of(ANONYMOUS, "POST", "/operator/api/reports", 401),
			Case.of(ANONYMOUS, "POST", "/operator/api/reports", 403).withoutCsrfToken(),
			Case.of(ANONYMOUS, "GET", "/operator/accessibility-report/page", 302).redirectsTo("/operator/login"),
			Case.of(ANONYMOUS, "GET", "/operator", 302).redirectsTo("/operator/login"),
			Case.of(ANONYMOUS, "GET", "/operator/", 302).redirectsTo("/operator/login"),
			Case.of(ANONYMOUS, "GET", "/operator/apis/reports", 302).redirectsTo("/operator/login"),
			Case.of(ANONYMOUS, "GET", "/operator/API/reports", 302).redirectsTo("/operator/login"),
			Case.of(ANONYMOUS, "GET", "/operator/Api", 302).redirectsTo("/operator/login"),
			Case.of(ANONYMOUS, "GET", "/operator/login/", 302).redirectsTo("/operator/login"),
			Case.of(ANONYMOUS, "GET", "/operator/Login", 302).redirectsTo("/operator/login"),
			Case.of(ANONYMOUS, "GET", "/operator/api/reports", 204).header("HX-Request", "true"),
			Case.of(ANONYMOUS, "GET", "/operator/accessibility-report/page", 204).header("HX-Request", "true"),
			// 대소문자가 다른 접두사는 기본 차단 체인으로 간다.
			Case.of(OPERATOR, "GET", "/OPERATOR/accessibility-report/page", 403),
			Case.of(ANONYMOUS, "GET", "/Operator/api/reports", 403),
			Case.of(OPERATOR, "GET", "/operatorx", 403),
			// 방화벽이 거부하는 변형.
			Case.of(OPERATOR, "GET", "/operator//accessibility-report/page", 400),
			Case.of(OPERATOR, "GET", "/operator/api//reports", 400),
			Case.of(OPERATOR, "GET", "/operator/api;x=1/reports", 400),
			Case.of(OPERATOR, "GET", "/operator/%2e%2e/admin/dashboard/page", 400),
			Case.of(OPERATOR, "GET", "/operator/api%2freports", 400)
		).map(Arguments::of);
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("operatorChainCases")
	@DisplayName("operator 체인 선택과 인증 진입점은 경로 변형에도 같은 결과를 낸다")
	void operatorChainBoundary(Case testCase) throws Exception {
		assertOutcome(testCase);
	}

	// ---------------------------------------------------------------- 로그아웃 matcher (#528)

	/** 로그아웃 화면 하나의 로그인 경로·세션 확인 경로·성공 이동 경로. */
	private record LogoutPortal(String loginPath, String username, String sessionProbePath, String successPath) {
	}

	private static final LogoutPortal OPERATOR_PORTAL = new LogoutPortal(
		"/operator/login", "boundary-operator", "/operator/accessibility-report/page", "/operator/login?logout");
	private static final LogoutPortal ADMIN_PORTAL = new LogoutPortal(
		"/admin/login", "boundary-super", "/admin/dashboard/page", "/admin/login?logout");

	/**
	 * 로그아웃으로 인정되는 요청(CSRF 토큰이 있는 POST, 정확히 /operator/logout)과 인정되지 않는 변형.
	 * GET은 다른 사이트가 이미지 태그 등으로 사용자를 강제 로그아웃시킬 수 있어 세션을 끝내면 안 된다.
	 */
	static Stream<Arguments> operatorLogoutCases() {
		return Stream.of(
			Arguments.of("POST", "/operator/logout", true, true),
			Arguments.of("POST", "/operator/logout?next=/admin", true, true),
			Arguments.of("POST", "/operator/%6cogout", true, true),
			Arguments.of("POST", "/operator/logou%74", true, true),
			Arguments.of("POST", "/operator/logout", false, false),
			Arguments.of("GET", "/operator/logout", true, false),
			Arguments.of("GET", "/operator/logout?next=/admin", true, false),
			Arguments.of("GET", "/operator/%6cogout", true, false),
			Arguments.of("HEAD", "/operator/logout", true, false),
			Arguments.of("PUT", "/operator/logout", true, false),
			Arguments.of("DELETE", "/operator/logout", true, false),
			Arguments.of("POST", "/operator/logout/", true, false),
			Arguments.of("POST", "/operator/Logout", true, false),
			Arguments.of("POST", "/operator/LOGOUT", true, false),
			Arguments.of("POST", "/operator/logout.html", true, false),
			Arguments.of("POST", "/operator/logout/x", true, false),
			Arguments.of("POST", "/operator/logouts", true, false),
			Arguments.of("POST", "/operator/x/logout", true, false),
			Arguments.of("POST", "/operator/api/logout", true, false),
			Arguments.of("POST", "/operator//logout", true, false),
			Arguments.of("POST", "/operator/logout;x=1", true, false),
			Arguments.of("POST", "/operator/logout%2f", true, false),
			Arguments.of("POST", "/operator/%2e/logout", true, false),
			Arguments.of("POST", "/OPERATOR/logout", true, false),
			Arguments.of("POST", "/operator/login/logout", true, false)
		);
	}

	@ParameterizedTest(name = "{0} {1} CSRF 토큰={2} 로그아웃 처리={3}")
	@MethodSource("operatorLogoutCases")
	@DisplayName("operator 로그아웃은 CSRF 토큰이 있는 POST로 정확한 경로에서만 세션을 종료한다")
	void operatorLogoutEndsSessionOnlyOnCsrfPostToExactPath(
		String method, String path, boolean sendsCsrfToken, boolean endsSession
	) throws Exception {
		assertLogoutOutcome(OPERATOR_PORTAL, method, path, sendsCsrfToken, endsSession);
	}

	/** admin 로그아웃도 같은 규칙이다. 기본 설정(CSRF 보호 + logoutUrl)이 POST만 받는다는 점을 고정한다. */
	static Stream<Arguments> adminLogoutCases() {
		return Stream.of(
			Arguments.of("POST", "/admin/logout", true, true),
			Arguments.of("POST", "/admin/logout", false, false),
			Arguments.of("GET", "/admin/logout", true, false),
			Arguments.of("GET", "/admin/logout?next=/admin", true, false),
			Arguments.of("PUT", "/admin/logout", true, false),
			Arguments.of("POST", "/admin/logout/", true, false),
			Arguments.of("POST", "/admin/Logout", true, false),
			Arguments.of("POST", "/ADMIN/logout", true, false)
		);
	}

	@ParameterizedTest(name = "{0} {1} CSRF 토큰={2} 로그아웃 처리={3}")
	@MethodSource("adminLogoutCases")
	@DisplayName("admin 로그아웃은 CSRF 토큰이 있는 POST로 정확한 경로에서만 세션을 종료한다")
	void adminLogoutEndsSessionOnlyOnCsrfPostToExactPath(
		String method, String path, boolean sendsCsrfToken, boolean endsSession
	) throws Exception {
		assertLogoutOutcome(ADMIN_PORTAL, method, path, sendsCsrfToken, endsSession);
	}

	private void assertLogoutOutcome(
		LogoutPortal portal, String method, String path, boolean sendsCsrfToken, boolean endsSession
	) throws Exception {
		Map<String, String> cookies = new HashMap<>();
		loginWithForm(cookies, portal.loginPath(), portal.username());
		assertThat(statusOf(cookies, "GET", portal.sessionProbePath()))
			.as("로그인 직후 세션 인증").isEqualTo(200);

		Case request = Case.of(SESSION_ONLY, method, path, 0);
		Exchange logout = exchange(cookies, sendsCsrfToken ? request : request.withoutCsrfToken());

		if (endsSession) {
			assertThat(logout.status()).isEqualTo(302);
			assertThat(logout.locationPath()).isEqualTo(portal.successPath());
			assertThat(statusOf(cookies, "GET", portal.sessionProbePath()))
				.as("로그아웃 뒤 세션 인증 해제").isNotEqualTo(200);
		} else {
			assertThat(logout.locationPath()).as("로그아웃 성공 이동이 아니어야 한다")
				.isNotEqualTo(portal.successPath());
			assertThat(statusOf(cookies, "GET", portal.sessionProbePath()))
				.as("로그아웃되지 않은 세션은 유지").isEqualTo(200);
		}
	}

	// ---------------------------------------------------------------- 신고·공개 체인

	static Stream<Arguments> reportAndPublicChainCases() {
		return Stream.of(
			// 신고 체인: 지정한 5개 경로만 permitAll, 나머지는 기본 차단 체인.
			Case.of(ANONYMOUS, "POST", "/api/v1/report-uploads", 200),
			Case.of(ANONYMOUS, "GET", "/api/v1/report-uploads/abc", 200),
			Case.of(ANONYMOUS, "POST", "/api/v1/reports", 200),
			Case.of(ANONYMOUS, "GET", "/api/v1/reports/abc", 200),
			Case.of(ANONYMOUS, "POST", "/api/v1/reports/abc/confirm", 200),
			Case.of(ANONYMOUS, "GET", "/api/v1/reports/abc/photo", 403),
			Case.of(ANONYMOUS, "GET", "/api/v1/reports/abc/confirm/x", 403),
			Case.of(ANONYMOUS, "GET", "/api/v1/report-uploads/abc/x", 403),
			Case.of(ANONYMOUS, "GET", "/api/v1/Reports/abc", 403),
			Case.of(ANONYMOUS, "GET", "/API/v1/reports/abc", 403),
			Case.of(ANONYMOUS, "GET", "/api/v1/reports/abc/", 403),
			// 빈 경로 구간은 체인에 매칭되지 않아 기본 차단 체인이 거부한다(#528). 신고 컨트롤러에도 이 경로는 없다.
			Case.of(ANONYMOUS, "GET", "/api/v1/reports/", 403),
			Case.of(ANONYMOUS, "POST", "/api/v1/reports/", 403),
			Case.of(ANONYMOUS, "GET", "/api/v1/report-uploads/", 403),
			Case.of(ANONYMOUS, "PUT", "/api/v1/report-uploads/", 403),
			Case.of(ANONYMOUS, "GET", "/api/v1/%72eports/abc", 200),
			Case.of(ANONYMOUS, "GET", "/api/v1/reports//abc", 400),
			Case.of(ANONYMOUS, "GET", "/api/v1/reports/abc;x=1", 400),
			Case.of(ANONYMOUS, "GET", "/api/v1/reports/%2e%2e/admin", 400),
			// 공개 체인: 목록에 있는 경로·메서드만 permitAll.
			Case.of(ANONYMOUS, "POST", "/api/ads/events", 200),
			Case.of(ANONYMOUS, "GET", "/api/ads/events", 403),
			Case.of(ANONYMOUS, "PUT", "/api/ads/events", 403),
			Case.of(ANONYMOUS, "GET", "/api/v1/trains/stations", 200),
			Case.of(ANONYMOUS, "GET", "/api/v1/trains/search", 200),
			Case.of(ANONYMOUS, "HEAD", "/api/v1/trains/stations", 200),
			Case.of(ANONYMOUS, "HEAD", "/api/v1/trains/search", 200),
			Case.of(ANONYMOUS, "POST", "/api/v1/trains/search", 403),
			Case.of(ANONYMOUS, "GET", "/api/v1/trains/search/", 403),
			Case.of(ANONYMOUS, "GET", "/api/v1/trains/Search", 403),
			Case.of(ANONYMOUS, "GET", "/", 200),
			Case.of(ANONYMOUS, "GET", "/index.html", 200),
			Case.of(ANONYMOUS, "GET", "/Index.html", 403),
			Case.of(ANONYMOUS, "POST", "/", 403),
			Case.of(ANONYMOUS, "GET", "/api/health", 200),
			Case.of(ANONYMOUS, "GET", "/api/health/", 403),
			Case.of(ANONYMOUS, "GET", "/api/Health", 403),
			Case.of(ANONYMOUS, "GET", "/actuator/health", 200),
			Case.of(ANONYMOUS, "GET", "/actuator/health/liveness", 200),
			Case.of(ANONYMOUS, "GET", "/actuator/health/readiness", 200),
			Case.of(ANONYMOUS, "GET", "/actuator/health/other", 403),
			Case.of(ANONYMOUS, "GET", "/actuator/env", 403),
			Case.of(ANONYMOUS, "GET", "/actuator", 403),
			Case.of(ANONYMOUS, "GET", "/actuator/prometheus", 403),
			Case.of(SUPER_ADMIN, "GET", "/actuator/prometheus", 403),
			Case.of(ANONYMOUS, "GET", "/privacy", 200),
			Case.of(ANONYMOUS, "GET", "/easysubway/privacy", 200),
			Case.of(ANONYMOUS, "GET", "/terms", 200),
			Case.of(ANONYMOUS, "GET", "/easysubway/terms", 200),
			Case.of(ANONYMOUS, "GET", "/location-terms", 200),
			Case.of(ANONYMOUS, "GET", "/easysubway/location-terms", 200),
			Case.of(ANONYMOUS, "GET", "/privacy/x", 403),
			Case.of(ANONYMOUS, "GET", "/Privacy", 403),
			Case.of(ANONYMOUS, "GET", "/api/v1/realtime", 200),
			Case.of(ANONYMOUS, "GET", "/api/v1/realtime/arrivals/1", 200),
			Case.of(ANONYMOUS, "GET", "/api/v1/REALTIME/arrivals", 403),
			Case.of(ANONYMOUS, "GET", "/api/notices/active", 200),
			Case.of(ANONYMOUS, "GET", "/api/ads/active", 200),
			Case.of(ANONYMOUS, "GET", "/api/notices/active/x", 403),
			Case.of(ANONYMOUS, "GET", "/favicon.ico", 200),
			Case.of(ANONYMOUS, "GET", "/css/app.css", 200),
			Case.of(ANONYMOUS, "GET", "/js/app.js", 200),
			Case.of(ANONYMOUS, "GET", "/vendor/lib/a.js", 200),
			Case.of(ANONYMOUS, "GET", "/images/a.png", 200),
			Case.of(ANONYMOUS, "GET", "/icons/a.svg", 200),
			Case.of(ANONYMOUS, "GET", "/webjars/x/y.js", 200),
			Case.of(ANONYMOUS, "GET", "/CSS/app.css", 403),
			Case.of(ANONYMOUS, "GET", "/css", 200),
			Case.of(ANONYMOUS, "GET", "/css/../admin/dashboard/page", 400),
			Case.of(ANONYMOUS, "GET", "/css/%2e%2e/admin/dashboard/page", 400),
			Case.of(ANONYMOUS, "GET", "/css//app.css", 400),
			// 정의되지 않은 경로는 모두 기본 차단.
			Case.of(ANONYMOUS, "GET", "/api/v1/unknown", 403),
			Case.of(SUPER_ADMIN, "GET", "/api/v1/unknown", 403),
			Case.of(ANONYMOUS, "GET", "/error", 403)
		).map(Arguments::of);
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("reportAndPublicChainCases")
	@DisplayName("신고·공개 체인은 허용 목록 밖의 경로 변형을 모두 거부한다")
	void reportAndPublicChainBoundary(Case testCase) throws Exception {
		assertOutcome(testCase);
	}

	// ---------------------------------------------------------------- 데이터팩 워크플로 체인

	static Stream<Arguments> datapackWorkflowChainCases() {
		return Stream.of(
			Case.of(WORKFLOW, "GET", "/admin/api/datapack/release-requests/req-1", 200),
			Case.of(WORKFLOW, "GET", "/admin/api/datapack/release-requests", 200),
			Case.of(WORKFLOW, "GET", "/admin/api/datapack/release-requests/", 200),
			Case.of(WORKFLOW, "POST", "/admin/api/datapack/release-requests/req-1/x", 200),
			Case.of(WORKFLOW, "GET", "/admin/api/datapack/rollback-approvals/ev-1", 200),
			Case.of(WORKFLOW, "POST", "/admin/api/datapack/release-callbacks", 200),
			Case.of(WORKFLOW, "POST", "/admin/api/datapack/%72elease-callbacks", 200),
			Case.of(WORKFLOW, "POST", "/admin/api/datapack/release-callbacks/", 401),
			Case.of(WORKFLOW, "POST", "/admin/api/datapack/release-callbacks/x", 401),
			Case.of(WORKFLOW, "POST", "/admin/api/datapack/automation-status", 403),
			Case.of(STATUS, "POST", "/admin/api/datapack/automation-status", 200),
			Case.of(STATUS, "GET", "/admin/api/datapack/automation-status", 403),
			Case.of(STATUS, "POST", "/admin/api/datapack/automation-status/", 401),
			Case.of(STATUS, "POST", "/admin/api/datapack/Automation-Status", 401),
			Case.of(STATUS, "POST", "/admin/api/datapack/%61utomation-status", 200),
			Case.of(STATUS, "GET", "/admin/api/datapack/release-requests/req-1", 403),
			Case.of(STATUS, "POST", "/admin/api/datapack/release-callbacks", 403),
			Case.of(STATUS, "GET", "/admin/api/datapack/rollback-approvals/ev-1", 403),
			// 체인 밖 경로는 서비스 토큰이 있어도 admin 체인의 세션·Basic 인증을 요구한다.
			Case.of(WORKFLOW, "GET", "/admin/api/datapack/Release-Requests/req-1", 401),
			Case.of(WORKFLOW, "GET", "/ADMIN/api/datapack/release-requests/req-1", 403),
			Case.of(WORKFLOW, "GET", "/admin/api/datapack/other", 401),
			Case.of(WORKFLOW, "GET", "/admin/api/datapack/release-requests-x", 401),
			Case.of(WORKFLOW, "GET", "/admin/api/datapack/release-requests//req-1", 400),
			Case.of(WORKFLOW, "GET", "/admin/api/datapack/release-requests;x=1/req-1", 400),
			Case.of(WORKFLOW, "GET", "/admin/api/datapack/release-requests/%2e%2e/automation-status", 400),
			Case.of(ANONYMOUS, "GET", "/admin/api/datapack/release-requests/req-1", 403),
			Case.of(new Caller("wrong-token", "Bearer wrong"), "GET",
				"/admin/api/datapack/release-requests/req-1", 403)
		).map(Arguments::of);
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("datapackWorkflowChainCases")
	@DisplayName("데이터팩 워크플로 체인은 정확한 경로·토큰 조합만 허용한다")
	void datapackWorkflowChainBoundary(Case testCase) throws Exception {
		assertOutcome(testCase);
	}

	// ---------------------------------------------------------------- 경로 목록 ↔ 테스트 표 1:1 대응

	/** SecurityConfig가 한 규칙으로 묶은 경로 목록. method가 null이면 모든 method에 적용되는 규칙이다. */
	private record PathGroup(String name, String method, String[] patterns) {

		Stream<GroupPattern> groupPatterns() {
			return Arrays.stream(patterns).map(pattern -> new GroupPattern(name, method, pattern));
		}
	}

	private record GroupPattern(String group, String method, String pattern) {

		@Override
		public String toString() {
			return group + (method == null ? "" : " " + method) + " " + pattern;
		}
	}

	/** 허용 행: admin 권한 규칙 표의 (method, 경로)와 모든 표에서 200으로 기대하는 케이스의 (method, 경로). */
	private record AllowedRow(String method, String path) {
	}

	private static final List<PathGroup> PATH_GROUPS = List.of(
		new PathGroup("DATAPACK_WORKFLOW_CHAIN_PATHS", null, SecurityConfig.DATAPACK_WORKFLOW_CHAIN_PATHS),
		new PathGroup("DATAPACK_WORKFLOW_PATHS", null, SecurityConfig.DATAPACK_WORKFLOW_PATHS),
		new PathGroup("ADMIN_REPORT_PHOTO_PATHS", "GET", SecurityConfig.ADMIN_REPORT_PHOTO_PATHS),
		new PathGroup("ADMIN_MASTER_EDIT_READ_PATHS", "GET", SecurityConfig.ADMIN_MASTER_EDIT_READ_PATHS),
		new PathGroup("ADMIN_MASTER_EDIT_WRITE_PATHS", "POST", SecurityConfig.ADMIN_MASTER_EDIT_WRITE_PATHS),
		new PathGroup("ADMIN_MASTER_EDIT_WRITE_PATHS", "PUT", SecurityConfig.ADMIN_MASTER_EDIT_WRITE_PATHS),
		new PathGroup("ADMIN_MASTER_EDIT_WRITE_PATHS", "PATCH", SecurityConfig.ADMIN_MASTER_EDIT_WRITE_PATHS),
		new PathGroup("ADMIN_DATA_OPERATE_PATHS", "POST", SecurityConfig.ADMIN_DATA_OPERATE_PATHS),
		new PathGroup("ADMIN_DATA_OPERATE_PATHS", "GET", SecurityConfig.ADMIN_DATA_OPERATE_PATHS),
		new PathGroup("ADMIN_DATAPACK_SOURCE_SNAPSHOT_PATHS", "POST", SecurityConfig.ADMIN_DATAPACK_SOURCE_SNAPSHOT_PATHS),
		new PathGroup("ADMIN_DATAPACK_REVIEW_POST_PATHS", "POST", SecurityConfig.ADMIN_DATAPACK_REVIEW_POST_PATHS),
		new PathGroup("ADMIN_OPERATIONS_PATHS", null, SecurityConfig.ADMIN_OPERATIONS_PATHS),
		new PathGroup("ADMIN_ERROR_PATHS", null, SecurityConfig.ADMIN_ERROR_PATHS),
		new PathGroup("ADMIN_SECURITY_AUDIT_PATHS", null, SecurityConfig.ADMIN_SECURITY_AUDIT_PATHS),
		new PathGroup("REPORT_CHAIN_PATHS", null, SecurityConfig.REPORT_CHAIN_PATHS),
		new PathGroup("PUBLIC_TRAIN_PATHS", "GET", SecurityConfig.PUBLIC_TRAIN_PATHS),
		new PathGroup("PUBLIC_TRAIN_PATHS", "HEAD", SecurityConfig.PUBLIC_TRAIN_PATHS),
		new PathGroup("PUBLIC_ROOT_PATHS", "GET", SecurityConfig.PUBLIC_ROOT_PATHS),
		new PathGroup("PUBLIC_OPEN_PATHS", null, SecurityConfig.PUBLIC_OPEN_PATHS)
	);

	static Stream<Arguments> pathGroupPatterns() {
		return PATH_GROUPS.stream().flatMap(PathGroup::groupPatterns).map(Arguments::of);
	}

	/**
	 * 경로 목록의 모든 항목은 그 항목에 해당하는 허용 행이 표에 있어야 한다. 항목을 추가하면서 표 행을 빠뜨리면
	 * 실패하고, 항목을 지우면 같은 행이 허용에서 거부로 바뀌어 위 표 테스트가 실패한다.
	 */
	@ParameterizedTest(name = "{0}")
	@MethodSource("pathGroupPatterns")
	@DisplayName("SecurityConfig 경로 목록의 모든 항목에 경계 표 행이 있다")
	void everyPathGroupPatternHasBoundaryRow(GroupPattern groupPattern) {
		PathPattern pattern = PathPatternParser.defaultInstance.parse(groupPattern.pattern());

		boolean covered = allowedRows().stream()
			.filter(row -> groupPattern.method() == null || groupPattern.method().equals(row.method()))
			.anyMatch(row -> pattern.matches(PathContainer.parsePath(row.path())));

		assertThat(covered).as(groupPattern + "에 대응하는 허용 행").isTrue();
	}

	private static List<AllowedRow> allowedRows() {
		Stream<AllowedRow> ruleRows = ADMIN_RULES.stream().map(rule -> new AllowedRow(rule.method(), rule.path()));
		Stream<AllowedRow> caseRows = Stream.of(
				adminChainBoundaryCases(),
				operatorChainCases(),
				reportAndPublicChainCases(),
				datapackWorkflowChainCases()
			)
			.flatMap(cases -> cases)
			.map(arguments -> (Case) arguments.get()[0])
			.filter(testCase -> testCase.status() == 200)
			.map(testCase -> new AllowedRow(testCase.method(), withoutQuery(testCase.path())));
		return Stream.concat(ruleRows, caseRows).toList();
	}

	private static String withoutQuery(String path) {
		int query = path.indexOf('?');
		return query < 0 ? path : path.substring(0, query);
	}
}
