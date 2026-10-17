package com.easysubway.common.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.easysubway.admin.audit.adapter.out.persistence.InMemoryAdminAuditEventRepository;
import com.easysubway.admin.authorization.AdminPermission;
import com.easysubway.admin.authorization.adapter.out.persistence.InMemoryAdminRbacAuthorityRepository;
import com.easysubway.admin.identity.adapter.out.persistence.InMemoryAdminIdentityRepository;
import com.easysubway.admin.identity.domain.AdminIdentity;
import com.easysubway.admin.identity.domain.AdminIdentityAuthMethod;
import com.easysubway.admin.identity.domain.AdminIdentityRole;
import com.easysubway.admin.identity.domain.AdminIdentityStatus;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.http.HttpMessageConvertersAutoConfiguration;
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration;
import org.springframework.boot.autoconfigure.security.servlet.SecurityFilterAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.DispatcherServletAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.ServletWebServerFactoryAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.WebMvcAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.error.ErrorMvcAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * SecurityConfig의 모든 URL matcher 경계를 실제 Tomcat 위에서 고정한다(#526).
 * Spring Security의 matcher 구현을 바꿔도(AntPathRequestMatcher → PathPatternRequestMatcher) 허용·거부
 * 결과가 달라지지 않아야 하므로, 컨테이너가 pathInfo를 디코딩하고 StrictHttpFirewall이 요청을 거르는
 * 실제 경로 처리까지 포함해 검증한다. 모든 행은 구현 교체 전에 통과한 기대값이다.
 */
@SpringBootTest(
	classes = SecurityConfigPathBoundaryTest.BoundaryApplication.class,
	webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
	properties = {
		"easysubway.admin.username=boundary-super",
		"easysubway.admin.password=" + SecurityConfigPathBoundaryTest.PASSWORD,
		"easysubway.operator.username=boundary-operator",
		"easysubway.operator.password=" + SecurityConfigPathBoundaryTest.PASSWORD,
		"easysubway.user.username=boundary-user",
		"easysubway.user.password=" + SecurityConfigPathBoundaryTest.PASSWORD,
		"easysubway.datapack.workflow-token=" + SecurityConfigPathBoundaryTest.WORKFLOW_TOKEN,
		"easysubway.datapack.automation-status-token=" + SecurityConfigPathBoundaryTest.STATUS_TOKEN
	}
)
@DisplayName("보안 설정 경로 경계")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SecurityConfigPathBoundaryTest {

	static final String PASSWORD = "boundary-pass";
	static final String WORKFLOW_TOKEN = "workflow-secret";
	static final String STATUS_TOKEN = "automation-status-secret";

	private static final String CSRF_PROBE_HEADER = "X-Probe-Csrf";
	private static final Set<String> CSRF_EXEMPT_METHODS = Set.of("GET", "HEAD", "OPTIONS", "TRACE");

	private static final Caller ANONYMOUS = new Caller("anonymous", null);
	/** Authorization 헤더 없이 세션 쿠키만으로 요청하는 호출자. */
	private static final Caller SESSION_ONLY = new Caller("session", null);
	private static final Caller SUPER_ADMIN = Caller.basic("boundary-super");
	private static final Caller OPERATOR = Caller.basic("boundary-operator");
	private static final Caller PLAIN_USER = Caller.basic("boundary-user");
	private static final Caller WORKFLOW = Caller.bearer(WORKFLOW_TOKEN);
	private static final Caller STATUS = Caller.bearer(STATUS_TOKEN);

	private static final HttpClient CLIENT = HttpClient.newBuilder()
		.version(HttpClient.Version.HTTP_1_1)
		.followRedirects(HttpClient.Redirect.NEVER)
		.build();

	@LocalServerPort
	private int port;

	@Autowired
	private InMemoryAdminIdentityRepository identityRepository;

	@Autowired
	private InMemoryAdminRbacAuthorityRepository rbacRepository;

	@Autowired
	private PasswordEncoder passwordEncoder;

	@BeforeAll
	void registerPermissionCallers() {
		LocalDateTime now = LocalDateTime.now();
		// bcrypt 해시 계산이 느리므로 모든 permission caller가 같은 해시를 공유한다.
		String passwordHash = passwordEncoder.encode(PASSWORD);
		for (AdminPermission permission : AdminPermission.values()) {
			String loginId = permissionCaller(permission).loginId();
			identityRepository.save(new AdminIdentity(
				loginId, loginId, null, passwordHash,
				AdminIdentityAuthMethod.LOCAL, AdminIdentityRole.ADMIN, AdminIdentityStatus.ACTIVE,
				0, null, now, null, false, null, false, now, now
			));
			// 모든 callers는 ADMIN_VIEW(anyRequest 최소 권한)에 더해 검사 대상 permission 하나만 가진다.
			rbacRepository.replacePermissionAuthorities(
				loginId,
				Set.copyOf(List.of(AdminPermission.ADMIN_VIEW.authority(), permission.authority()))
			);
		}
	}

	// ---------------------------------------------------------------- admin 권한 규칙

	/** (method, 구체 경로, 필요한 권한 중 하나). hasAnyAuthority 규칙은 권한마다 한 행이 아니라 목록으로 둔다. */
	private record AdminRule(String method, String path, List<AdminPermission> anyOf) {

		static AdminRule of(String method, String path, AdminPermission... anyOf) {
			return new AdminRule(method, path, List.of(anyOf));
		}
	}

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
		AdminRule.of("POST", "/admin/datapack/alias-approvals/1",
			AdminPermission.DATAPACK_ALIAS_REVIEW, AdminPermission.DATAPACK_QUARANTINE_REVIEW,
			AdminPermission.DATAPACK_EVIDENCE_REVIEW, AdminPermission.DATAPACK_OVERRIDE_REQUEST,
			AdminPermission.DATAPACK_OVERRIDE_APPROVE, AdminPermission.DATAPACK_CANDIDATE_BUILD),
		AdminRule.of("POST", "/admin/datapack/candidates/1",
			AdminPermission.DATAPACK_ALIAS_REVIEW, AdminPermission.DATAPACK_QUARANTINE_REVIEW,
			AdminPermission.DATAPACK_EVIDENCE_REVIEW, AdminPermission.DATAPACK_OVERRIDE_REQUEST,
			AdminPermission.DATAPACK_OVERRIDE_APPROVE, AdminPermission.DATAPACK_CANDIDATE_BUILD),
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

	// ---------------------------------------------------------------- operator 로그아웃 matcher

	/** 로그아웃으로 인정되는 요청(메서드 무관, 정확히 /operator/logout)과 인정되지 않는 변형. */
	static Stream<Arguments> logoutCases() {
		return Stream.of(
			Arguments.of("GET", "/operator/logout", true),
			Arguments.of("GET", "/operator/logout?next=/admin", true),
			Arguments.of("GET", "/operator/%6cogout", true),
			Arguments.of("GET", "/operator/logou%74", true),
			Arguments.of("POST", "/operator/logout", true),
			Arguments.of("GET", "/operator/logout/", false),
			Arguments.of("GET", "/operator/Logout", false),
			Arguments.of("GET", "/operator/LOGOUT", false),
			Arguments.of("GET", "/operator/logout.html", false),
			Arguments.of("GET", "/operator/logout/x", false),
			Arguments.of("GET", "/operator/logouts", false),
			Arguments.of("GET", "/operator/x/logout", false),
			Arguments.of("GET", "/operator/api/logout", false),
			Arguments.of("GET", "/operator//logout", false),
			Arguments.of("GET", "/operator/logout;x=1", false),
			Arguments.of("GET", "/operator/logout%2f", false),
			Arguments.of("GET", "/operator/%2e/logout", false),
			Arguments.of("GET", "/OPERATOR/logout", false),
			Arguments.of("GET", "/operator/login/logout", false)
		);
	}

	@ParameterizedTest(name = "{0} {1} 로그아웃 처리={2}")
	@MethodSource("logoutCases")
	@DisplayName("operator 로그아웃은 정확한 경로에서만 세션을 종료한다")
	void operatorLogoutEndsSessionOnlyOnExactPath(String method, String path, boolean endsSession) throws Exception {
		Map<String, String> cookies = new HashMap<>();
		loginOperatorWithForm(cookies);
		assertThat(statusOf(cookies, "GET", "/operator/accessibility-report/page"))
			.as("로그인 직후 세션 인증").isEqualTo(200);

		Exchange logout = exchange(cookies, Case.of(SESSION_ONLY, method, path, 0));

		if (endsSession) {
			assertThat(logout.status()).isEqualTo(302);
			assertThat(logout.locationPath()).isEqualTo("/operator/login?logout");
			assertThat(statusOf(cookies, "GET", "/operator/accessibility-report/page"))
				.as("로그아웃 뒤 세션 인증 해제").isEqualTo(302);
		} else {
			assertThat(logout.locationPath()).as("로그아웃 성공 이동이 아니어야 한다")
				.isNotEqualTo("/operator/login?logout");
			assertThat(statusOf(cookies, "GET", "/operator/accessibility-report/page"))
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
			Case.of(ANONYMOUS, "GET", "/api/v1/reports/", 200),
			Case.of(ANONYMOUS, "GET", "/api/v1/%72eports/abc", 200),
			Case.of(ANONYMOUS, "GET", "/api/v1/reports//abc", 400),
			Case.of(ANONYMOUS, "GET", "/api/v1/reports/abc;x=1", 400),
			Case.of(ANONYMOUS, "GET", "/api/v1/reports/%2e%2e/admin", 400),
			// 공개 체인: 목록에 있는 경로·메서드만 permitAll.
			Case.of(ANONYMOUS, "POST", "/api/ads/events", 200),
			Case.of(ANONYMOUS, "GET", "/api/ads/events", 403),
			Case.of(ANONYMOUS, "PUT", "/api/ads/events", 403),
			Case.of(ANONYMOUS, "GET", "/api/v1/trains/stations", 200),
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

	// ---------------------------------------------------------------- 실행 helper

	private void assertOutcome(Case testCase) throws IOException, InterruptedException {
		Exchange result = exchange(new HashMap<>(), testCase);

		assertThat(result.status()).as(testCase.toString()).isEqualTo(testCase.status());
		if (testCase.location() != null) {
			assertThat(result.locationPath()).as(testCase + " Location").isEqualTo(testCase.location());
		}
	}

	private int statusOf(Map<String, String> cookies, String method, String path)
		throws IOException, InterruptedException {
		return exchange(cookies, Case.of(SESSION_ONLY, method, path, 0)).status();
	}

	private void loginOperatorWithForm(Map<String, String> cookies) throws IOException, InterruptedException {
		String csrf = csrfTokenFor(cookies, "/operator/login");
		String form = "username=boundary-operator&password=" + PASSWORD + "&_csrf=" + csrf;
		HttpRequest.Builder request = HttpRequest.newBuilder(uri("/operator/login"))
			.header("Content-Type", "application/x-www-form-urlencoded")
			.POST(HttpRequest.BodyPublishers.ofString(form));
		HttpResponse<Void> login = send(cookies, request);
		assertThat(login.statusCode()).isEqualTo(302);
	}

	/**
	 * 요청 하나를 보낸다. admin/operator 체인의 상태 변경 요청은 CSRF 토큰이 필요하므로, 같은 세션으로
	 * 공개 로그인 페이지에서 토큰을 먼저 받아 헤더로 붙인다. 토큰은 호출자 인증과 무관한 전제다.
	 */
	private Exchange exchange(Map<String, String> cookies, Case testCase) throws IOException, InterruptedException {
		HttpRequest.Builder request = HttpRequest.newBuilder(uri(testCase.path()))
			.method(testCase.method(), HttpRequest.BodyPublishers.noBody());
		if (testCase.caller().authorization() != null) {
			request.header("Authorization", testCase.caller().authorization());
		}
		testCase.headers().forEach(request::header);
		if (testCase.sendsCsrfToken() && !CSRF_EXEMPT_METHODS.contains(testCase.method())) {
			request.header("X-CSRF-TOKEN", csrfTokenFor(cookies, testCase.path()));
		}
		HttpResponse<Void> response = send(cookies, request);
		return new Exchange(response.statusCode(), response.headers().firstValue("Location").orElse(null));
	}

	private String csrfTokenFor(Map<String, String> cookies, String path)
		throws IOException, InterruptedException {
		String loginPage = path.startsWith("/operator") ? "/operator/login" : "/admin/login";
		// 세션 토큰은 호출자 인증과 무관하므로 비용이 큰 Basic 인증 없이 공개 로그인 페이지에서 받는다.
		HttpRequest.Builder probe = HttpRequest.newBuilder(uri(loginPage)).GET();
		return send(cookies, probe).headers().firstValue(CSRF_PROBE_HEADER).orElse("");
	}

	/** 쿠키 저장소를 직접 유지해 JDK HttpClient 하나를 모든 요청이 공유한다(클라이언트 생성이 느리다). */
	private HttpResponse<Void> send(Map<String, String> cookies, HttpRequest.Builder request)
		throws IOException, InterruptedException {
		if (!cookies.isEmpty()) {
			request.header("Cookie", cookies.entrySet().stream()
				.map(cookie -> cookie.getKey() + "=" + cookie.getValue())
				.collect(Collectors.joining("; ")));
		}
		HttpResponse<Void> response = CLIENT.send(request.build(), HttpResponse.BodyHandlers.discarding());
		for (String setCookie : response.headers().allValues("Set-Cookie")) {
			String nameAndValue = setCookie.split(";", 2)[0];
			int separator = nameAndValue.indexOf('=');
			String name = nameAndValue.substring(0, separator);
			String value = nameAndValue.substring(separator + 1);
			if (value.isEmpty()) {
				cookies.remove(name);
			} else {
				cookies.put(name, value);
			}
		}
		return response;
	}

	private URI uri(String rawPathAndQuery) {
		return URI.create("http://localhost:" + port + rawPathAndQuery);
	}

	private static Caller permissionCaller(AdminPermission permission) {
		return Caller.basic("perm-" + permission.name().toLowerCase(Locale.ROOT).replace('_', '-'));
	}

	private static String basicHeader(String loginId, String password) {
		String credentials = loginId + ":" + password;
		return "Basic " + Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
	}

	/** Authorization 헤더로 식별되는 호출자. */
	private record Caller(String label, String authorization) {

		static Caller basic(String loginId) {
			return new Caller(loginId, basicHeader(loginId, PASSWORD));
		}

		static Caller bearer(String token) {
			return new Caller("bearer", "Bearer " + token);
		}

		String loginId() {
			return label;
		}
	}

	private record Exchange(int status, String location) {

		String locationPath() {
			if (location == null) {
				return null;
			}
			return location.replaceFirst("^https?://[^/]+", "");
		}
	}

	private record Case(
		Caller caller,
		String method,
		String path,
		Map<String, String> headers,
		int status,
		String location,
		boolean sendsCsrfToken
	) {

		static Case of(Caller caller, String method, String path, int status) {
			return new Case(caller, method, path, Map.of(), status, null, true);
		}

		Case header(String name, String value) {
			Map<String, String> merged = new LinkedHashMap<>(headers);
			merged.put(name, value);
			return new Case(caller, method, path, merged, status, location, sendsCsrfToken);
		}

		Case accept(String mediaType) {
			return header("Accept", mediaType);
		}

		Case redirectsTo(String redirectPath) {
			return new Case(caller, method, path, headers, status, redirectPath, sendsCsrfToken);
		}

		Case withoutCsrfToken() {
			return new Case(caller, method, path, headers, status, location, false);
		}

		@Override
		public String toString() {
			String suffix = (headers.isEmpty() ? "" : " " + headers) + (sendsCsrfToken ? "" : " (CSRF 토큰 없음)");
			return caller.label() + " " + method + " " + path + suffix + " -> " + status
				+ (location == null ? "" : " " + location);
		}
	}

	// ---------------------------------------------------------------- 테스트 애플리케이션

	@Configuration
	@ImportAutoConfiguration({
		ServletWebServerFactoryAutoConfiguration.class,
		DispatcherServletAutoConfiguration.class,
		WebMvcAutoConfiguration.class,
		HttpMessageConvertersAutoConfiguration.class,
		ErrorMvcAutoConfiguration.class,
		SecurityAutoConfiguration.class,
		SecurityFilterAutoConfiguration.class
	})
	@Import({SecurityConfig.class, BoundaryProbeController.class})
	static class BoundaryApplication {

		@Bean
		InMemoryAdminIdentityRepository adminIdentityRepository() {
			return new InMemoryAdminIdentityRepository();
		}

		@Bean
		InMemoryAdminRbacAuthorityRepository adminRbacAuthorityRepository() {
			return new InMemoryAdminRbacAuthorityRepository();
		}

		@Bean
		InMemoryAdminAuditEventRepository adminAuditEventRepository() {
			return new InMemoryAdminAuditEventRepository();
		}
	}

	/** 보안 필터를 통과한 모든 요청에 200을 돌려주고, 세션 CSRF 토큰을 헤더로 노출한다. */
	@RestController
	static class BoundaryProbeController {

		@RequestMapping("/**")
		ResponseEntity<String> probe(
			@RequestAttribute(name = "_csrf", required = false) CsrfToken csrf,
			@RequestAttribute(name = "adminErrorStatus", required = false) Integer deniedStatus
		) {
			// 접근 거부 핸들러가 forward한 관리자 오류 화면은 원래 거부 상태코드를 그대로 유지한다.
			ResponseEntity.BodyBuilder response = deniedStatus == null
				? ResponseEntity.ok()
				: ResponseEntity.status(deniedStatus);
			if (csrf != null) {
				response.header(CSRF_PROBE_HEADER, csrf.getToken());
			}
			return response.body("ok");
		}
	}
}
