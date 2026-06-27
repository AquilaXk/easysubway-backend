package com.easysubway.admin.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;

@DisplayName("관리자 HTML 요청 판별")
class AdminHtmlRequestTest {

	@Test
	@DisplayName("JSON Accept admin API 요청은 form content type이어도 HTML로 분류하지 않는다")
	void jsonAcceptAdminApiRequestDoesNotMatchHtml() {
		MockHttpServletRequest request = new MockHttpServletRequest("POST", "/admin/data-sources/unknown/sync");
		request.addHeader("Accept", MediaType.APPLICATION_JSON_VALUE);
		request.setContentType(MediaType.APPLICATION_FORM_URLENCODED_VALUE);

		assertThat(AdminHtmlRequest.matches(request)).isFalse();
	}

	@Test
	@DisplayName("관리자 page form 요청은 HTML로 분류한다")
	void adminPageFormRequestMatchesHtml() {
		MockHttpServletRequest request = new MockHttpServletRequest("POST", "/admin/facilities/page/status");
		request.setContentType(MediaType.APPLICATION_FORM_URLENCODED_VALUE);

		assertThat(AdminHtmlRequest.matches(request)).isTrue();
	}

	@Test
	@DisplayName("context path가 붙은 관리자 page 요청도 내부 admin HTML 경로로 분류한다")
	void adminPageRequestWithContextPathMatchesHtml() {
		MockHttpServletRequest request = new MockHttpServletRequest("GET", "/subway/admin/facilities/page");
		request.setContextPath("/subway");

		assertThat(AdminHtmlRequest.pathWithinApplication(request)).isEqualTo("/admin/facilities/page");
		assertThat(AdminHtmlRequest.matches(request)).isTrue();
	}

	@Test
	@DisplayName("form content type은 대소문자와 charset 파라미터가 달라도 인식한다")
	void formContentTypeMatchesWithParametersAndCaseDifferences() {
		MockHttpServletRequest request = new MockHttpServletRequest("POST", "/admin/incidents");
		request.setContentType("Application/X-WWW-Form-Urlencoded; charset=UTF-8");

		assertThat(AdminHtmlRequest.isFormUrlEncoded(request)).isTrue();
		assertThat(AdminHtmlRequest.matches(request)).isTrue();
	}
}
