package com.easysubway.support;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class EgovFrameRuntimeTest {

	@Test
	void egovFrameMvcRuntimeIsOnClasspath() throws ClassNotFoundException {
		Class<?> paginationInfo = Class.forName("org.egovframe.rte.ptl.mvc.tags.ui.pagination.PaginationInfo");

		assertThat(paginationInfo.getPackageName()).isEqualTo("org.egovframe.rte.ptl.mvc.tags.ui.pagination");
	}
}
