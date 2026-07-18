package com.easysubway.admin.navigation;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
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
			"ROLE_ADMIN",
			"admin.security.audit",
			"admin.view"
		);

		AdminNavigationAdvice.AdminShell shell = new AdminNavigationAdvice(environment).adminShell(authentication);

		assertThat(shell.environmentLabel()).isEqualTo("PRODUCTION");
		assertThat(shell.environmentTone()).isEqualTo("production");
		assertThat(shell.username()).isEqualTo("ops-admin");
		assertThat(shell.rolesLabel()).isEqualTo("ADMIN");
		assertThat(shell.revision()).isEqualTo("main-20260627");
		assertThat(shell.masterDataVersion()).isEqualTo("datapack-20260627");
	}

	@Test
	@DisplayName("역할 등급이 여러 개면 콤마로 정렬해 이어붙이고, 세부 RBAC 권한(authority) 개수는 드러내지 않는다")
	void rolesLabelHidesPermissionAuthorityCount() {
		MockEnvironment environment = new MockEnvironment();
		TestingAuthenticationToken authentication = new TestingAuthenticationToken(
			"ops-admin",
			"ignored",
			"ROLE_OPERATOR",
			"ROLE_ADMIN",
			"admin.security.audit",
			"admin.view",
			"admin.push.send"
		);

		AdminNavigationAdvice.AdminShell shell = new AdminNavigationAdvice(environment).adminShell(authentication);

		assertThat(shell.rolesLabel()).isEqualTo("ADMIN, OPERATOR");
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

	// #2272 V6-00: 관리자 화면 inventory를 source assertion으로 고정한다. 조사 수치를 하드코딩하지 않고
	// enum 자체에서 세어 29개 surface와 각 항목의 id·path·permission 완결성을 검증한다. route·permission·
	// behavior(visibleTo())는 변경하지 않으며 v6 이관 중 화면 수가 흔들리면 이 테스트가 실패해야 한다.
	@Test
	@DisplayName("AdminProgram은 29개 관리자 surface를 고정하고 각 항목은 id·path·permission을 모두 갖는다")
	void adminProgramRegistryPinsAdminSurfaceInventory() {
		assertThat(AdminProgram.values()).hasSize(29);

		for (AdminProgram program : AdminProgram.values()) {
			assertThat(program.id())
				.as("%s id", program.name())
				.isNotBlank();
			assertThat(program.path())
				.as("%s path", program.name())
				.startsWith("/admin/")
				.endsWith("/page");
			assertThat(program.permission())
				.as("%s permission", program.name())
				.isNotNull();
			assertThat(program.groupLabel())
				.as("%s groupLabel", program.name())
				.isNotBlank();
			assertThat(program.label())
				.as("%s label", program.name())
				.isNotBlank();
		}

		// id와 path는 surface 정본이므로 중복 없이 유일해야 한다.
		assertThat(Arrays.stream(AdminProgram.values()).map(AdminProgram::id).distinct().count()).isEqualTo(29);
		assertThat(Arrays.stream(AdminProgram.values()).map(AdminProgram::path).distinct().count()).isEqualTo(29);
	}
}
