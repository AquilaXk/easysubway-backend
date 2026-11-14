package com.easysubway.admin.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.easysubway.common.error.ConflictException;
import jakarta.servlet.http.HttpSession;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;

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
				.isInstanceOf(ConflictException.class);
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
	@DisplayName("application/json REST API POST와 /admin/login은 commandToken 인터셉터를 우회한다")
	void allowsJsonApiAndLoginPostsWithoutCommandToken() {
		MockHttpServletRequest jsonRequest = new MockHttpServletRequest("POST", "/admin/notices");
		jsonRequest.setContentType(MediaType.APPLICATION_JSON_VALUE);
		assertThat(interceptor.preHandle(jsonRequest, new MockHttpServletResponse(), new Object())).isTrue();

		MockHttpServletRequest loginRequest = new MockHttpServletRequest("POST", "/admin/login");
		loginRequest.setContentType(MediaType.APPLICATION_FORM_URLENCODED_VALUE);
		assertThat(interceptor.preHandle(loginRequest, new MockHttpServletResponse(), new Object())).isTrue();
	}
}
