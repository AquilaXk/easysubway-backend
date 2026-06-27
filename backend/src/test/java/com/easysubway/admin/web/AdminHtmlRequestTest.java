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
}
