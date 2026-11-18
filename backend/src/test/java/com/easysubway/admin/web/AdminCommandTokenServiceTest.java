package com.easysubway.admin.web;

import static org.assertj.core.api.Assertions.assertThatCode;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

@DisplayName("관리자 command token service")
class AdminCommandTokenServiceTest {

	private final AdminCommandTokenService service = new AdminCommandTokenService();

	@Test
	@DisplayName("row action form이 많은 admin 화면도 먼저 렌더링된 token을 유지한다")
	void keepsRenderedRowActionTokensBeyondDatapackListSize() {
		MockHttpServletRequest request = new MockHttpServletRequest();
		String firstRenderedToken = service.issue(request);
		for (int index = 1; index < 700; index++) {
			service.issue(request);
		}

		assertThatCode(() -> service.consume(request, firstRenderedToken))
			.doesNotThrowAnyException();
	}
}
