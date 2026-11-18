package com.easysubway.admin.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import jakarta.servlet.http.HttpSession;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import java.util.List;

@DisplayName("관리자 command token interceptor")
class AdminCommandTokenInterceptorTest {

	private final AdminCommandTokenService tokenService = new AdminCommandTokenService();
	private final AdminCommandTokenInterceptor interceptor = new AdminCommandTokenInterceptor(tokenService);

	@Test
	@DisplayName("multipart/form-data와 text/plain 관리자 POST는 Accept JSON이어도 commandToken 없이는 차단된다")
	void blocksMultipartAndTextPlainAdminPostsEvenWithJsonAccept() {
		for (String contentType : new String[] {
			"multipart/form-data; boundary=----AdminBoundary",
			"text/plain; charset=UTF-8"
		}) {
			MockHttpServletRequest request = new MockHttpServletRequest("POST", "/admin/notices");
			request.addHeader("Accept", MediaType.APPLICATION_JSON_VALUE);
			request.setContentType(contentType);

			assertThatThrownBy(() -> interceptor.preHandle(request, new MockHttpServletResponse(), new Object()))
				.isInstanceOf(AccessDeniedException.class);
		}
	}

	@Test
	@DisplayName("세션 인증 상태의 모든 /admin 상태 변경(POST/PUT/PATCH/DELETE)은 Accept·Content-Type과 무관하게 토큰 없으면 403 차단된다")
	void enforcesCommandTokenForAllSessionAuthenticatedStateChangingMethodsRegardlessOfHeaders() {
		MockHttpSession session = new MockHttpSession();
		for (String method : new String[] { "POST", "PUT", "PATCH", "DELETE" }) {
			MockHttpServletRequest request = new MockHttpServletRequest(method, "/admin/notices/123");
			request.setSession(session);
			request.addHeader("Accept", MediaType.APPLICATION_JSON_VALUE);
			request.setContentType(MediaType.APPLICATION_JSON_VALUE);

			assertThatThrownBy(() -> interceptor.preHandle(request, new MockHttpServletResponse(), new Object()))
				.isInstanceOf(AccessDeniedException.class);
		}

		MockHttpServletRequest issueRequest = new MockHttpServletRequest("GET", "/admin/notices/page");
		issueRequest.setSession(session);
		String validToken = tokenService.issue(issueRequest);

		MockHttpServletRequest patchWithHeader = new MockHttpServletRequest("PATCH", "/admin/notices/123");
		patchWithHeader.setSession(session);
		patchWithHeader.addHeader("Accept", MediaType.APPLICATION_JSON_VALUE);
		patchWithHeader.setContentType(MediaType.APPLICATION_JSON_VALUE);
		patchWithHeader.addHeader(AdminCommandTokenService.HEADER_NAME, validToken);

		assertThat(interceptor.preHandle(patchWithHeader, new MockHttpServletResponse(), new Object())).isTrue();
		assertThatThrownBy(() -> interceptor.preHandle(patchWithHeader, new MockHttpServletResponse(), new Object()))
			.isInstanceOf(AccessDeniedException.class);
	}

	@Test
	@DisplayName("SecurityContext 기반 세션 인증만 있고 HTTP 세션이 아직 없어도 JSON 요청에 commandToken을 강제한다")
	void enforcesCommandTokenWhenSecurityContextSessionAuthWithoutHttpSession() {
		var auth = new UsernamePasswordAuthenticationToken(
			"admin-user",
			"N/A",
			List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))
		);
		org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(auth);
		try {
			MockHttpServletRequest jsonDelete = new MockHttpServletRequest("DELETE", "/admin/ads/1");
			jsonDelete.addHeader("Accept", MediaType.APPLICATION_JSON_VALUE);
			jsonDelete.setContentType(MediaType.APPLICATION_JSON_VALUE);

			assertThatThrownBy(() -> interceptor.preHandle(jsonDelete, new MockHttpServletResponse(), new Object()))
				.isInstanceOf(AccessDeniedException.class);
		} finally {
			org.springframework.security.core.context.SecurityContextHolder.clearContext();
		}
	}

	@Test
	@DisplayName("유효한 commandToken이 있으면 multipart/form-data와 /page POST를 통과시킨다")
	void allowsValidCommandTokenForMultipartAndPagePosts() {
		MockHttpServletRequest issueRequest = new MockHttpServletRequest("GET", "/admin/notices/page");
		String token = tokenService.issue(issueRequest);
		HttpSession session = issueRequest.getSession(false);

		MockHttpServletRequest multipartRequest = new MockHttpServletRequest("POST", "/admin/notices");
		multipartRequest.setSession((MockHttpSession) session);
		multipartRequest.setContentType("multipart/form-data; boundary=----AdminBoundary");
		multipartRequest.setParameter(AdminCommandTokenService.PARAMETER_NAME, token);

		assertThat(interceptor.preHandle(multipartRequest, new MockHttpServletResponse(), new Object())).isTrue();

		String pageToken = tokenService.issue(issueRequest);
		MockHttpServletRequest pageRequest = new MockHttpServletRequest("POST", "/admin/facilities/editor/page");
		pageRequest.setSession((MockHttpSession) session);
		pageRequest.setParameter(AdminCommandTokenService.PARAMETER_NAME, pageToken);

		assertThat(interceptor.preHandle(pageRequest, new MockHttpServletResponse(), new Object())).isTrue();
	}

	@Test
	@DisplayName("stateless Authorization 헤더 기반 JSON REST API POST와 /admin/login은 commandToken 인터셉터를 우회한다")
	void allowsJsonApiAndLoginPostsWithoutCommandToken() {
		MockHttpServletRequest jsonRequest = new MockHttpServletRequest("POST", "/admin/notices");
		jsonRequest.addHeader("Authorization", "Basic YWRtaW46c2VjcmV0");
		jsonRequest.setContentType(MediaType.APPLICATION_JSON_VALUE);
		assertThat(interceptor.preHandle(jsonRequest, new MockHttpServletResponse(), new Object())).isTrue();

		MockHttpServletRequest loginRequest = new MockHttpServletRequest("POST", "/admin/login");
		loginRequest.setContentType(MediaType.APPLICATION_FORM_URLENCODED_VALUE);
		assertThat(interceptor.preHandle(loginRequest, new MockHttpServletResponse(), new Object())).isTrue();
	}
}
