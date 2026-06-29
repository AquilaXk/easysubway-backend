package com.easysubway.admin.navigation;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;

@DisplayName("관리자 공통 shell 모델")
class AdminNavigationAdviceTest {

	@Test
	@DisplayName("prod profile은 운영 환경 badge와 배포 revision, 마스터 데이터 버전을 표시한다")
	void prodProfileBuildsProductionShellMetadata() {
		MockEnvironment environment = new MockEnvironment()
			.withProperty("easysubway.admin.revision", "main-20260627")
			.withProperty("easysubway.admin.master-data-version", "datapack-20260627");
		environment.setActiveProfiles("prod");
		TestingAuthenticationToken authentication = new TestingAuthenticationToken(
			"ops-admin",
			"ignored",
			"admin.security.audit",
			"admin.view"
		);

		AdminNavigationAdvice.AdminShell shell = new AdminNavigationAdvice(environment).adminShell(authentication);

		assertThat(shell.environmentLabel()).isEqualTo("PRODUCTION");
		assertThat(shell.environmentTone()).isEqualTo("production");
		assertThat(shell.username()).isEqualTo("ops-admin");
		assertThat(shell.rolesLabel()).isEqualTo("admin.security.audit 외 1개");
		assertThat(shell.revision()).isEqualTo("main-20260627");
		assertThat(shell.masterDataVersion()).isEqualTo("datapack-20260627");
	}

	@Test
	@DisplayName("staging profile은 staging badge를 표시하고 기본 revision 값을 유지한다")
	void stagingProfileBuildsStagingShellMetadata() {
		MockEnvironment environment = new MockEnvironment();
		environment.setActiveProfiles("staging");
		TestingAuthenticationToken authentication = new TestingAuthenticationToken(
			"release-admin",
			"ignored",
			"admin.view"
		);

		AdminNavigationAdvice.AdminShell shell = new AdminNavigationAdvice(environment).adminShell(authentication);

		assertThat(shell.environmentLabel()).isEqualTo("STAGING");
		assertThat(shell.environmentTone()).isEqualTo("staging");
		assertThat(shell.revision()).isEqualTo("local");
		assertThat(shell.masterDataVersion()).isEqualTo("unknown");
	}

	@Test
	@DisplayName("staging profile은 prod 설정을 함께 로드해도 staging badge를 유지한다")
	void stagingProfileKeepsStagingShellMetadataWhenProdProfileIsImported() {
		MockEnvironment environment = new MockEnvironment();
		environment.setActiveProfiles("staging", "prod");
		TestingAuthenticationToken authentication = new TestingAuthenticationToken(
			"release-admin",
			"ignored",
			"admin.view"
		);

		AdminNavigationAdvice.AdminShell shell = new AdminNavigationAdvice(environment).adminShell(authentication);

		assertThat(shell.environmentLabel()).isEqualTo("STAGING");
		assertThat(shell.environmentTone()).isEqualTo("staging");
	}

	@Test
	@DisplayName("익명 인증은 anonymous 사용자와 권한 없음으로 표시한다")
	void anonymousAuthenticationBuildsAnonymousShellMetadata() {
		MockEnvironment environment = new MockEnvironment();
		AnonymousAuthenticationToken authentication = new AnonymousAuthenticationToken(
			"key",
			"anonymousUser",
			AuthorityUtils.createAuthorityList("ROLE_ANONYMOUS")
		);

		AdminNavigationAdvice.AdminShell shell = new AdminNavigationAdvice(environment).adminShell(authentication);

		assertThat(shell.username()).isEqualTo("anonymous");
		assertThat(shell.rolesLabel()).isEqualTo("권한 없음");
		assertThat(shell.environmentLabel()).isEqualTo("DEV");
	}

	@Test
	@DisplayName("인증 정보가 없으면 anonymous 사용자와 권한 없음으로 표시한다")
	void nullAuthenticationBuildsAnonymousShellMetadata() {
		AdminNavigationAdvice.AdminShell shell = new AdminNavigationAdvice(new MockEnvironment()).adminShell(null);

		assertThat(shell.username()).isEqualTo("anonymous");
		assertThat(shell.rolesLabel()).isEqualTo("권한 없음");
		assertThat(shell.revision()).isEqualTo("local");
		assertThat(shell.masterDataVersion()).isEqualTo("unknown");
	}
}
