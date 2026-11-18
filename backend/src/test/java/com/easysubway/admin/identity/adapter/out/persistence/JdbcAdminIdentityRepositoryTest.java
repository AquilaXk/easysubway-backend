package com.easysubway.admin.identity.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.easysubway.admin.identity.domain.AdminIdentity;
import com.easysubway.admin.identity.domain.AdminIdentityAuthMethod;
import com.easysubway.admin.identity.domain.AdminIdentityRole;
import com.easysubway.admin.identity.domain.AdminIdentityStatus;
import com.easysubway.admin.identity.domain.AdminLoginAudit;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Optional;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

@DisplayName("JDBC 관리자 identity 저장소")
class JdbcAdminIdentityRepositoryTest {

	@Test
	@DisplayName("관리자 계정 상태와 로그인 감사 기록을 DB에 저장한다")
	void saveIdentityAndLoginAudit() {
		var dataSource = adminIdentityDataSource();
		var repository = new JdbcAdminIdentityRepository(dataSource);
		var jdbcTemplate = new JdbcTemplate(dataSource);
		LocalDateTime now = LocalDateTime.of(2026, 6, 27, 0, 0);

		repository.save(localIdentity("admin-user", now));
		repository.recordLoginFailure("admin-user", now.plusMinutes(1), 1, Duration.ofMinutes(15));
		repository.recordLoginAudit(new AdminLoginAudit(
			"admin-user",
			AdminIdentityAuthMethod.LOCAL,
			"LOCKED",
			null,
			now.plusMinutes(1)
		));

		var loaded = repository.findByLoginId("ADMIN-USER").orElseThrow();
		assertThat(loaded.failedLoginCount()).isEqualTo(1);
		assertThat(loaded.lockedUntil()).isEqualTo(now.plusMinutes(16));
		assertThat(loaded.role()).isEqualTo(AdminIdentityRole.ADMIN);
		assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM admin_login_audits", Integer.class))
			.isEqualTo(1);
	}

	@Test
	@DisplayName("동시 bootstrap 중 중복 insert가 발생하면 기존 계정을 반환한다")
	void upsertBootstrapReturnsExistingIdentityAfterDuplicateInsert() {
		var dataSource = adminIdentityDataSource();
		var repository = new JdbcAdminIdentityRepository(dataSource);
		LocalDateTime now = LocalDateTime.of(2026, 6, 27, 0, 0);
		LocalDateTime existingCreatedAt = now.minusDays(1);
		AdminIdentity persisted = localIdentity("admin-user", existingCreatedAt);
		AdminIdentity bootstrap = localIdentity("admin-user", now);
		repository.save(persisted);

		var staleReadRepository = new JdbcAdminIdentityRepository(dataSource) {
			private int findAttempts;

			@Override
			public Optional<AdminIdentity> findByLoginId(String loginId) {
				if (findAttempts++ == 0) {
					return Optional.empty();
				}
				return super.findByLoginId(loginId);
			}
		};

		var loaded = staleReadRepository.upsertBootstrap(bootstrap);

		assertThat(loaded.loginId()).isEqualTo("admin-user");
		assertThat(loaded.createdAt()).isEqualTo(existingCreatedAt);
		assertThat(repository.findByLoginId("admin-user")).hasValueSatisfying(existing ->
			assertThat(existing.createdAt()).isEqualTo(existingCreatedAt)
		);
	}

	@Test
	@DisplayName("현재 bootstrap 설정에 없는 bootstrap-managed 계정만 비활성화한다")
	void disableStaleBootstrapIdentitiesOnlyDisablesManagedRows() {
		var dataSource = adminIdentityDataSource();
		var repository = new JdbcAdminIdentityRepository(dataSource);
		LocalDateTime now = LocalDateTime.of(2026, 6, 27, 0, 0);
		repository.save(localIdentity("active-admin", now));
		repository.save(localIdentity("stale-admin", now));
		repository.save(localIdentity("manual-admin", now, false));

		int disabled = repository.disableStaleBootstrapIdentities(
			java.util.Set.of("active-admin"),
			now.plusMinutes(1)
		);

		assertThat(disabled).isEqualTo(1);
		assertThat(repository.findByLoginId("active-admin").orElseThrow().status())
			.isEqualTo(AdminIdentityStatus.ACTIVE);
		assertThat(repository.findByLoginId("stale-admin").orElseThrow().status())
			.isEqualTo(AdminIdentityStatus.DISABLED);
		assertThat(repository.findByLoginId("manual-admin").orElseThrow().status())
			.isEqualTo(AdminIdentityStatus.ACTIVE);
	}

	private DataSource adminIdentityDataSource() {
		var dataSource = new EmbeddedDatabaseBuilder()
			.setType(EmbeddedDatabaseType.H2)
			.generateUniqueName(true)
			.build();
		new ResourceDatabasePopulator(new ClassPathResource("db/migration/h2/V9__admin_identity.sql"))
			.execute(dataSource);
		return dataSource;
	}

	private AdminIdentity localIdentity(String loginId, LocalDateTime now) {
		return localIdentity(loginId, now, true);
	}

	private AdminIdentity localIdentity(String loginId, LocalDateTime now, boolean bootstrapManaged) {
		return new AdminIdentity(
			loginId,
			"관리자",
			"admin@example.com",
			"{noop}admin-password",
			AdminIdentityAuthMethod.LOCAL,
			AdminIdentityRole.ADMIN,
			AdminIdentityStatus.ACTIVE,
			0,
			null,
			now,
			null,
			false,
			null,
			bootstrapManaged,
			now,
			now
		);
	}
}
