package com.easysubway.common.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.easysubway.admin.identity.adapter.out.persistence.InMemoryAdminIdentityRepository;
import com.easysubway.admin.identity.domain.AdminIdentity;
import com.easysubway.admin.identity.domain.AdminIdentityAuthMethod;
import com.easysubway.admin.identity.domain.AdminIdentityRole;
import com.easysubway.admin.identity.domain.AdminIdentityStatus;
import java.time.LocalDateTime;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest(properties = {
	"easysubway.admin.username=admin-user",
	"easysubway.admin.password=admin-test-password",
	"easysubway.operator.username=operator-user",
	"easysubway.operator.password=operator-test-password"
})
@AutoConfigureMockMvc
@DisplayName("관리자·운영기관 로그인 공개 notice")
class LoginNoticeSecurityTest {

	private static final String RETRY_WARNING_COPY = "아이디 또는 비밀번호를 확인하고 다시 시도해 주세요.";

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private InMemoryAdminIdentityRepository identityRepository;

	@Autowired
	private PasswordEncoder passwordEncoder;

	@Test
	@DisplayName("정상 화면은 동일한 로그인 카피만 제공한다")
	void normalPagesKeepCopyParity() throws Exception {
		String adminHtml = normalLoginHtml(LoginSurface.ADMIN);
		String operatorHtml = normalLoginHtml(LoginSurface.OPERATOR);

		assertThat(adminHtml)
			.contains(">로그인</button>")
			.doesNotContain("안전하게 로그인", "역·시설 데이터와 사용자 제보를 안전하게 관리합니다.", RETRY_WARNING_COPY);
		assertThat(operatorHtml)
			.contains(">로그인</button>")
			.doesNotContain("안전하게 로그인", "기관 담당자에게 발급된 계정으로 접근성 보고서를 확인하세요.", RETRY_WARNING_COPY);
	}

	@ParameterizedTest(name = "{0}")
	@EnumSource(LoginSurface.class)
	@DisplayName("GET과 query parameter는 경고 상태를 주입할 수 없다")
	void getAndQueryCannotInjectWarning(LoginSurface surface) throws Exception {
		String html = mockMvc.perform(get(surface.loginPath)
				.param("loginNotice", "RETRY_WARNING")
				.param("error", "locked"))
			.andExpect(status().isOk())
			.andExpect(model().attribute("loginNotice", LoginNotice.NONE))
			.andReturn()
			.getResponse()
			.getContentAsString();

		assertThat(html).doesNotContain(RETRY_WARNING_COPY, "role=\"alert\"");
	}

	@ParameterizedTest(name = "{0} {1}")
	@MethodSource("failureMatrix")
	@DisplayName("모든 backend identity 실패는 동일한 one-shot 경고로 응답한다")
	void identityFailuresShareOneShotPublicResponse(LoginSurface surface, FailureKind failure) throws Exception {
		String username = surface.name().toLowerCase() + "-" + failure.name().toLowerCase();
		String password = "correct-password";
		if (failure != FailureKind.UNKNOWN) {
			identityRepository.save(identity(username, password, surface.role, failure.status));
		}

		MockHttpSession session = new MockHttpSession();
		MvcResult failureResult = mockMvc.perform(post(surface.loginPath)
				.session(session)
				.with(csrf())
				.param("username", username)
				.param("password", failure == FailureKind.WRONG_PASSWORD ? "wrong-password" : password))
			.andExpect(status().is3xxRedirection())
			.andExpect(redirectedUrl(surface.loginPath))
			.andExpect(content().string(""))
			.andReturn();

		String warningHtml = mockMvc.perform(get(surface.loginPath).session(sessionFrom(failureResult)))
			.andExpect(status().isOk())
			.andExpect(model().attribute("loginNotice", LoginNotice.RETRY_WARNING))
			.andReturn()
			.getResponse()
			.getContentAsString();
		assertThat(warningHtml)
			.contains(RETRY_WARNING_COPY, "role=\"alert\"")
			.doesNotContain(username, failure.publicForbiddenCopy);

		String refreshedHtml = mockMvc.perform(get(surface.loginPath).session(sessionFrom(failureResult)))
			.andExpect(status().isOk())
			.andExpect(model().attribute("loginNotice", LoginNotice.NONE))
			.andReturn()
			.getResponse()
			.getContentAsString();
		assertThat(refreshedHtml).doesNotContain(RETRY_WARNING_COPY, "role=\"alert\"");
	}

	@ParameterizedTest(name = "{0}")
	@EnumSource(LoginSurface.class)
	@DisplayName("실패 warning은 다른 로그인 surface가 먼저 열려도 교차 소비되지 않는다")
	void warningIsScopedToLoginSurface(LoginSurface surface) throws Exception {
		MockHttpSession session = new MockHttpSession();
		MvcResult failureResult = mockMvc.perform(post(surface.loginPath)
				.session(session)
				.with(csrf())
				.param("username", "unknown-user")
				.param("password", "wrong-password"))
			.andExpect(status().is3xxRedirection())
			.andExpect(redirectedUrl(surface.loginPath))
			.andReturn();
		MockHttpSession failureSession = sessionFrom(failureResult);

		String otherSurfaceHtml = mockMvc.perform(get(surface.other().loginPath).session(failureSession))
			.andExpect(status().isOk())
			.andExpect(model().attribute("loginNotice", LoginNotice.NONE))
			.andReturn()
			.getResponse()
			.getContentAsString();
		assertThat(otherSurfaceHtml).doesNotContain(RETRY_WARNING_COPY, "role=\"alert\"");

		String originalSurfaceHtml = mockMvc.perform(get(surface.loginPath).session(failureSession))
			.andExpect(status().isOk())
			.andExpect(model().attribute("loginNotice", LoginNotice.RETRY_WARNING))
			.andReturn()
			.getResponse()
			.getContentAsString();
		assertThat(originalSurfaceHtml).contains(RETRY_WARNING_COPY, "role=\"alert\"");
	}

	@ParameterizedTest(name = "{0}")
	@EnumSource(LoginSurface.class)
	@DisplayName("missing·invalid CSRF는 403이고 경고 flash를 만들지 않는다")
	void csrfFailuresDoNotCreateNotice(LoginSurface surface) throws Exception {
		assertCsrfFailureHasNoNotice(surface, false);
		assertCsrfFailureHasNoNotice(surface, true);
	}

	private String normalLoginHtml(LoginSurface surface) throws Exception {
		return mockMvc.perform(get(surface.loginPath))
			.andExpect(status().isOk())
			.andExpect(model().attribute("loginNotice", LoginNotice.NONE))
			.andReturn()
			.getResponse()
			.getContentAsString();
	}

	private void assertCsrfFailureHasNoNotice(LoginSurface surface, boolean invalidToken) throws Exception {
		MockHttpSession session = new MockHttpSession();
		var request = post(surface.loginPath)
			.session(session)
			.param("username", "unknown-user")
			.param("password", "wrong-password");
		if (invalidToken) {
			request.param("_csrf", "invalid-token");
		}
		mockMvc.perform(request)
			.andExpect(status().isForbidden());

		String html = mockMvc.perform(get(surface.loginPath).session(session))
			.andExpect(status().isOk())
			.andExpect(model().attribute("loginNotice", LoginNotice.NONE))
			.andReturn()
			.getResponse()
			.getContentAsString();
		assertThat(html).doesNotContain(RETRY_WARNING_COPY, "role=\"alert\"");
	}

	private AdminIdentity identity(
		String username,
		String password,
		AdminIdentityRole role,
		AdminIdentityStatus status
	) {
		LocalDateTime now = LocalDateTime.now().minusDays(1);
		return new AdminIdentity(
			username,
			"테스트 관리자",
			null,
			passwordEncoder.encode(password),
			AdminIdentityAuthMethod.LOCAL,
			role,
			status,
			0,
			null,
			now,
			null,
			false,
			null,
			false,
			now,
			now
		);
	}

	private MockHttpSession sessionFrom(MvcResult result) {
		return (MockHttpSession) result.getRequest().getSession(false);
	}

	private static Stream<Arguments> failureMatrix() {
		return Stream.of(LoginSurface.values())
			.flatMap(surface -> Stream.of(FailureKind.values())
				.map(failure -> Arguments.of(surface, failure)));
	}

	private enum LoginSurface {
		ADMIN("/admin/login", AdminIdentityRole.ADMIN),
		OPERATOR("/operator/login", AdminIdentityRole.OPERATOR_ADMIN);

		private final String loginPath;
		private final AdminIdentityRole role;

		LoginSurface(String loginPath, AdminIdentityRole role) {
			this.loginPath = loginPath;
			this.role = role;
		}

		LoginSurface other() {
			return this == ADMIN ? OPERATOR : ADMIN;
		}
	}

	private enum FailureKind {
		UNKNOWN(AdminIdentityStatus.ACTIVE, "UNKNOWN"),
		WRONG_PASSWORD(AdminIdentityStatus.ACTIVE, "wrong-password"),
		LOCKED(AdminIdentityStatus.LOCKED, "LOCKED"),
		DISABLED(AdminIdentityStatus.DISABLED, "DISABLED"),
		EXPIRED(AdminIdentityStatus.PASSWORD_EXPIRED, "PASSWORD_EXPIRED");

		private final AdminIdentityStatus status;
		private final String publicForbiddenCopy;

		FailureKind(AdminIdentityStatus status, String publicForbiddenCopy) {
			this.status = status;
			this.publicForbiddenCopy = publicForbiddenCopy;
		}
	}
}
