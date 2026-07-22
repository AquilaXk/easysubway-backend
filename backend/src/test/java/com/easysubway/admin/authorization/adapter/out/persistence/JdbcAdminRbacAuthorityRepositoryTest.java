package com.easysubway.admin.authorization.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.easysubway.admin.authorization.AdminRbacRole;
import java.time.LocalDateTime;
import java.util.Set;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

@DisplayName("JDBC 관리자 RBAC authority 저장소")
class JdbcAdminRbacAuthorityRepositoryTest {

	@Test
	@DisplayName("SUPER_ADMIN role seed는 canonical 로그인 ID로 전체 permission authority를 부여한다")
	void seedSuperAdminRoleGrantsAllPermissionAuthorities() {
		var dataSource = rbacDataSource();
		var repository = new JdbcAdminRbacAuthorityRepository(dataSource);

		repository.seedRole("Env-Admin", AdminRbacRole.SUPER_ADMIN);

		assertThat(repository.findPermissionAuthorities("env-admin"))
			.contains(
				"admin.view",
				"admin.report.review",
				"admin.master.edit",
				"admin.field.operate",
				"admin.data.operate",
				"admin.security.audit",
				"admin.security.admin"
			);
	}

	@Test
	@DisplayName("동일 role seed를 반복해도 admin_user_roles 행은 하나만 유지한다")
	void seedRoleIsIdempotent() {
		var dataSource = rbacDataSource();
		var repository = new JdbcAdminRbacAuthorityRepository(dataSource);
		var jdbcTemplate = new JdbcTemplate(dataSource);

		repository.seedRole("env-admin", AdminRbacRole.SUPER_ADMIN);
		repository.seedRole("env-admin", AdminRbacRole.SUPER_ADMIN);

		assertThat(jdbcTemplate.queryForObject(
			"SELECT COUNT(*) FROM admin_user_roles WHERE login_id = ? AND role_code = ?",
			Integer.class,
			"env-admin",
			"SUPER_ADMIN"
		)).isEqualTo(1);
	}

	@Test
	@DisplayName("seed된 행은 granted_by='bootstrap' provenance를 남긴다")
	void seedRoleRecordsBootstrapProvenance() {
		var dataSource = rbacDataSource();
		var repository = new JdbcAdminRbacAuthorityRepository(dataSource);
		var jdbcTemplate = new JdbcTemplate(dataSource);

		repository.seedRole("env-admin", AdminRbacRole.SUPER_ADMIN);

		assertThat(jdbcTemplate.queryForObject(
			"SELECT granted_by FROM admin_user_roles WHERE login_id = ? AND role_code = ?",
			String.class,
			"env-admin",
			"SUPER_ADMIN"
		)).isEqualTo("bootstrap");
	}

	@Test
	@DisplayName("사전 INSERT된 동일 role 행이 있어도 seed는 예외 없이 멱등하게 통과한다")
	void seedRoleOnPreexistingRowIsNoOpWithoutException() {
		var dataSource = rbacDataSource();
		var repository = new JdbcAdminRbacAuthorityRepository(dataSource);
		var jdbcTemplate = new JdbcTemplate(dataSource);
		jdbcTemplate.update(
			"INSERT INTO admin_user_roles (created_at, role_code, login_id) VALUES (?, ?, ?)",
			LocalDateTime.now(),
			"SUPER_ADMIN",
			"env-admin"
		);

		assertThatCode(() -> repository.seedRole("env-admin", AdminRbacRole.SUPER_ADMIN))
			.doesNotThrowAnyException();

		assertThat(jdbcTemplate.queryForObject(
			"SELECT COUNT(*) FROM admin_user_roles WHERE login_id = ? AND role_code = ?",
			Integer.class,
			"env-admin",
			"SUPER_ADMIN"
		)).isEqualTo(1);
	}

	@Test
	@DisplayName("env에서 제거된 bootstrap-seeded 계정의 role은 회수하되 active 계정은 보존한다")
	void revokeStaleBootstrapRolesRemovesOnlyInactiveBootstrapRows() {
		var dataSource = rbacDataSource();
		var repository = new JdbcAdminRbacAuthorityRepository(dataSource);
		repository.seedRole("kept-admin", AdminRbacRole.SUPER_ADMIN);
		repository.seedRole("stale-admin", AdminRbacRole.SUPER_ADMIN);

		repository.revokeStaleBootstrapRoles(Set.of("kept-admin"));

		assertThat(repository.findPermissionAuthorities("kept-admin")).contains("admin.security.admin");
		assertThat(repository.findPermissionAuthorities("stale-admin")).isEmpty();
	}

	@Test
	@DisplayName("수동 부여(granted_by NULL) 행은 bootstrap 회수 대상에서 제외한다")
	void revokeStaleBootstrapRolesPreservesManualGrants() {
		var dataSource = rbacDataSource();
		var repository = new JdbcAdminRbacAuthorityRepository(dataSource);
		var jdbcTemplate = new JdbcTemplate(dataSource);
		// 운영자가 수동 부여한 role은 granted_by NULL로 존재한다.
		jdbcTemplate.update(
			"INSERT INTO admin_user_roles (created_at, role_code, login_id) VALUES (?, ?, ?)",
			LocalDateTime.now(),
			"SUPER_ADMIN",
			"manual-admin"
		);

		// active 목록이 비어도 수동 부여 행은 회수되지 않는다.
		repository.revokeStaleBootstrapRoles(Set.of());

		assertThat(repository.findPermissionAuthorities("manual-admin")).contains("admin.security.admin");
	}

	@Test
	@DisplayName("bootstrap 회수는 여러 번 호출해도 멱등하다")
	void revokeStaleBootstrapRolesIsIdempotent() {
		var dataSource = rbacDataSource();
		var repository = new JdbcAdminRbacAuthorityRepository(dataSource);
		repository.seedRole("stale-admin", AdminRbacRole.SUPER_ADMIN);

		repository.revokeStaleBootstrapRoles(Set.of());
		assertThatCode(() -> repository.revokeStaleBootstrapRoles(Set.of()))
			.doesNotThrowAnyException();

		assertThat(repository.findPermissionAuthorities("stale-admin")).isEmpty();
	}

	private DataSource rbacDataSource() {
		var dataSource = new EmbeddedDatabaseBuilder()
			.setType(EmbeddedDatabaseType.H2)
			.generateUniqueName(true)
			.build();
		new ResourceDatabasePopulator(
			new ClassPathResource("db/migration/h2/V10__admin_rbac_menu.sql"),
			new ClassPathResource("db/migration/h2/V67__admin_user_roles_granted_by.sql"),
			new ClassPathResource("db/migration/h2/V68__create_error_events.sql"),
			new ClassPathResource("db/migration/h2/V69__admin_error_events_permission.sql")
		).execute(dataSource);
		return dataSource;
	}
}
