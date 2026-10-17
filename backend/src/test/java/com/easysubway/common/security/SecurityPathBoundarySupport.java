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
import java.util.HashMap;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.TestInstance;
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
import org.springframework.boot.test.context.TestComponent;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.http.ResponseEntity;
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
/**
 * SecurityConfig URL matcher 경계 테스트의 공통 기반(#526). 실제 Tomcat 위에 SecurityConfig와 모든 요청에
 * 200을 돌려주는 probe controller만 올리고, JDK HttpClient로 원시 경로를 그대로 보낸다.
 * MockMvc는 pathInfo를 디코딩하지 않고 컨테이너의 경로 정규화·StrictHttpFirewall 거부를 재현하지 못하므로
 * 인코딩·이중 슬래시·경로 매개변수 경계는 실제 컨테이너가 필요하다.
 */
@SpringBootTest(
	classes = SecurityPathBoundarySupport.BoundaryApplication.class,
	webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
	properties = {
		"easysubway.admin.username=boundary-super",
		"easysubway.admin.password=" + SecurityPathBoundarySupport.PASSWORD,
		"easysubway.operator.username=boundary-operator",
		"easysubway.operator.password=" + SecurityPathBoundarySupport.PASSWORD,
		"easysubway.user.username=boundary-user",
		"easysubway.user.password=" + SecurityPathBoundarySupport.PASSWORD,
		"easysubway.datapack.workflow-token=" + SecurityPathBoundarySupport.WORKFLOW_TOKEN,
		"easysubway.datapack.automation-status-token=" + SecurityPathBoundarySupport.STATUS_TOKEN
	}
)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
abstract class SecurityPathBoundarySupport {

	static final String PASSWORD = "boundary-pass";
	static final String WORKFLOW_TOKEN = "workflow-secret";
	static final String STATUS_TOKEN = "automation-status-secret";

	private static final String CSRF_PROBE_HEADER = "X-Probe-Csrf";
	private static final Set<String> CSRF_EXEMPT_METHODS = Set.of("GET", "HEAD", "OPTIONS", "TRACE");

	static final Caller ANONYMOUS = new Caller("anonymous", null);
	/** Authorization 헤더 없이 세션 쿠키만으로 요청하는 호출자. */
	static final Caller SESSION_ONLY = new Caller("session", null);
	static final Caller SUPER_ADMIN = Caller.basic("boundary-super");
	static final Caller OPERATOR = Caller.basic("boundary-operator");
	static final Caller PLAIN_USER = Caller.basic("boundary-user");
	static final Caller WORKFLOW = Caller.bearer(WORKFLOW_TOKEN);
	static final Caller STATUS = Caller.bearer(STATUS_TOKEN);

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

	// ---------------------------------------------------------------- 실행 helper

	void assertOutcome(Case testCase) throws IOException, InterruptedException {
		Exchange result = exchange(new HashMap<>(), testCase);

		assertThat(result.status()).as(testCase.toString()).isEqualTo(testCase.status());
		if (testCase.location() != null) {
			assertThat(result.locationPath()).as(testCase + " Location").isEqualTo(testCase.location());
		}
	}

	int statusOf(Map<String, String> cookies, String method, String path)
		throws IOException, InterruptedException {
		return exchange(cookies, Case.of(SESSION_ONLY, method, path, 0)).status();
	}

	void loginOperatorWithForm(Map<String, String> cookies) throws IOException, InterruptedException {
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
	Exchange exchange(Map<String, String> cookies, Case testCase) throws IOException, InterruptedException {
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

	static Caller permissionCaller(AdminPermission permission) {
		return Caller.basic("perm-" + permission.name().toLowerCase(Locale.ROOT).replace('_', '-'));
	}

	static String basicHeader(String loginId, String password) {
		String credentials = loginId + ":" + password;
		return "Basic " + Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
	}

	/** Authorization 헤더로 식별되는 호출자. */
	record Caller(String label, String authorization) {

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

	record Exchange(int status, String location) {

		String locationPath() {
			if (location == null) {
				return null;
			}
			return location.replaceFirst("^https?://[^/]+", "");
		}
	}

	record Case(
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

	// @TestComponent가 없으면 실제 애플리케이션의 컴포넌트 스캔이 이 테스트 전용 빈을 줍는다. 이 기반 클래스는
	// @Test 메서드가 없어 TestTypeExcludeFilter가 테스트 클래스로 인식하지 못한다. @TestConfiguration 대신
	// @Configuration을 쓰는 이유는 @SpringBootTest가 이 클래스를 주 설정으로 쓰게 하기 위해서다.
	@TestComponent
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
	@TestComponent
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
