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

	@Test
	@DisplayName("전자정부프레임워크 FDL logging은 control-plane 검증용 클래스패스에만 존재한다")
	void egovFrameFdlLoggingRuntimeIsOnClasspathForControlPlaneOnly() throws ClassNotFoundException {
		Class<?> loggingUtility = Class.forName("org.egovframe.rte.fdl.logging.util.EgovResourceReleaser");

		assertThat(loggingUtility.getPackageName()).isEqualTo("org.egovframe.rte.fdl.logging.util");
	}

	@Test
	@DisplayName("전자정부프레임워크 bat-core 배치 변수 리스너가 control-plane 클래스패스에 존재한다")
	void egovFrameBatCoreRuntimeIsOnClasspathForControlPlaneOnly() throws ClassNotFoundException {
		Class<?> stepVariableListener = Class.forName("org.egovframe.rte.bat.support.EgovStepVariableListener");

		assertThat(stepVariableListener.getPackageName()).isEqualTo("org.egovframe.rte.bat.support");
	}
}
