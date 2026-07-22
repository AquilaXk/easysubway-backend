package com.easysubway.admin.navigation;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
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
	@DisplayName("AdminProgram은 30개 관리자 surface를 고정하고 각 항목은 id·path·permission을 모두 갖는다")
	void adminProgramRegistryPinsAdminSurfaceInventory() {
		assertThat(AdminProgram.values()).hasSize(30);

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
		assertThat(Arrays.stream(AdminProgram.values()).map(AdminProgram::id).distinct().count()).isEqualTo(30);
		assertThat(Arrays.stream(AdminProgram.values()).map(AdminProgram::path).distinct().count()).isEqualTo(30);
	}

	// #2277 V6-05: 7개 workspace와 §7 workspace→program 매핑을 source assertion으로 문자 그대로 고정한다.
	// 29개 program을 정확히 한 workspace에 배정하며 중복·누락은 grouping 결과가 기대 map과 달라 실패한다.
	// workspace 내부 program 순서(§7 표 나열 순 = AdminProgram 선언 순)도 List.equals로 함께 고정한다.
	@Test
	@DisplayName("AdminWorkspace는 7개 업무 영역과 §7 program 매핑을 중복·누락 없이 문자 그대로 고정한다")
	void adminWorkspaceMappingPinsSectionSevenContract() {
		assertThat(AdminWorkspace.values()).hasSize(7);
		for (AdminWorkspace workspace : AdminWorkspace.values()) {
			assertThat(workspace.id()).as("%s id", workspace.name()).isNotBlank();
			assertThat(workspace.displayName()).as("%s displayName", workspace.name()).isNotBlank();
		}

		// §7 표시명(문자 그대로).
		assertThat(AdminWorkspace.OVERVIEW.displayName()).isEqualTo("개요");
		assertThat(AdminWorkspace.ACCESSIBILITY_DATA.displayName()).isEqualTo("역·접근성 데이터");
		assertThat(AdminWorkspace.OPERATIONS.displayName()).isEqualTo("운영");
		assertThat(AdminWorkspace.COMMUNICATIONS.displayName()).isEqualTo("커뮤니케이션");
		assertThat(AdminWorkspace.ANALYTICS.displayName()).isEqualTo("분석");
		assertThat(AdminWorkspace.DATAPACK.displayName()).isEqualTo("데이터팩");
		assertThat(AdminWorkspace.SYSTEM_AUDIT.displayName()).isEqualTo("시스템·감사");

		// §7 workspace → 포함 program(문자 그대로, 순서까지).
		Map<AdminWorkspace, List<AdminProgram>> expected = new LinkedHashMap<>();
		expected.put(AdminWorkspace.OVERVIEW, List.of(AdminProgram.DASHBOARD));
		expected.put(AdminWorkspace.ACCESSIBILITY_DATA, List.of(
			AdminProgram.STATIONS, AdminProgram.FACILITIES, AdminProgram.LAYOUT_EDITOR,
			AdminProgram.REPORTS, AdminProgram.QUALITY, AdminProgram.FIELD));
		expected.put(AdminWorkspace.OPERATIONS, List.of(
			AdminProgram.COLLECTIONS, AdminProgram.BATCHES, AdminProgram.INCIDENTS));
		expected.put(AdminWorkspace.COMMUNICATIONS, List.of(
			AdminProgram.SERVICE_NOTICES, AdminProgram.ADS, AdminProgram.PUSH));
		expected.put(AdminWorkspace.ANALYTICS, List.of(
			AdminProgram.ROUTE_SEARCHES, AdminProgram.ROUTE_FEEDBACK, AdminProgram.USAGE));
		expected.put(AdminWorkspace.DATAPACK, List.of(
			AdminProgram.DATAPACK_PIPELINE, AdminProgram.DATAPACK_SOURCE_SNAPSHOTS,
			AdminProgram.DATAPACK_ALIAS_QUARANTINE, AdminProgram.DATAPACK_FACILITY_EVIDENCE,
			AdminProgram.DATAPACK_ROUTE_GATES, AdminProgram.DATAPACK_MANUAL_OVERRIDES,
			AdminProgram.DATAPACK_CANDIDATES, AdminProgram.DATAPACK_RELEASE_CHANNELS,
			AdminProgram.DATAPACK_RELEASE_REQUESTS));
		expected.put(AdminWorkspace.SYSTEM_AUDIT, List.of(
			AdminProgram.CODES, AdminProgram.SYSTEM, AdminProgram.AUDITS, AdminProgram.ERROR_EVENTS, AdminProgram.PRIVACY_AUDITS));

		Map<AdminWorkspace, List<AdminProgram>> actual = Arrays.stream(AdminProgram.values())
			.collect(Collectors.groupingBy(
				AdminProgram::workspace,
				() -> new EnumMap<>(AdminWorkspace.class),
				Collectors.toList()));

		assertThat(actual).isEqualTo(expected);
		// 30개 program이 정확히 한 workspace에 배정된다(중복·누락 0).
		assertThat(actual.values().stream().mapToInt(List::size).sum()).isEqualTo(30);
		assertThat(actual.keySet()).containsExactlyInAnyOrder(AdminWorkspace.values());
		for (AdminProgram program : AdminProgram.values()) {
			assertThat(program.workspace()).as("%s workspace", program.name()).isNotNull();
		}
	}

	// #2277 V6-05: shell IA. permission 필터(visibleTo) 뒤 program이 0개인 workspace는 렌더 목록에서
	// 제외하고, 남은 workspace는 AdminWorkspace enum 선언 순서로 정렬한다. admin.view만 가진 관리자는
	// OVERVIEW·ACCESSIBILITY_DATA·ANALYTICS 3개만 보고 나머지 4개는 program이 없어 제외된다.
	@Test
	@DisplayName("adminWorkspaces는 program이 0개인 workspace를 제외하고 enum 순서로 렌더한다")
	void adminWorkspacesDropsEmptyWorkspacesAndKeepsEnumOrder() {
		TestingAuthenticationToken viewer = new TestingAuthenticationToken("viewer", "ignored", "admin.view");

		List<AdminNavigationAdvice.AdminWorkspaceSection> sections =
			new AdminNavigationAdvice(new MockEnvironment()).adminWorkspaces(viewer);

		assertThat(sections)
			.extracting(AdminNavigationAdvice.AdminWorkspaceSection::label)
			.containsExactly("개요", "역·접근성 데이터", "분석");
		assertThat(sections).allSatisfy(section -> assertThat(section.programs()).isNotEmpty());
		assertThat(sections)
			.filteredOn(section -> section.label().equals("역·접근성 데이터"))
			.singleElement()
			.satisfies(section -> assertThat(section.programs())
				.extracting(AdminProgram::id)
				.containsExactly("a-stations", "a-facilities", "a-quality"));
	}
}
