package com.easysubway.support;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("전자정부프레임워크 런타임")
class EgovFrameRuntimeTest {

	@Test
	@DisplayName("전자정부프레임워크 MVC PaginationInfo가 클래스패스에 존재한다")
	void egovFrameMvcRuntimeIsOnClasspath() throws ClassNotFoundException {
		Class<?> paginationInfo = Class.forName("org.egovframe.rte.ptl.mvc.tags.ui.pagination.PaginationInfo");

		assertThat(paginationInfo.getPackageName()).isEqualTo("org.egovframe.rte.ptl.mvc.tags.ui.pagination");
	}
}
