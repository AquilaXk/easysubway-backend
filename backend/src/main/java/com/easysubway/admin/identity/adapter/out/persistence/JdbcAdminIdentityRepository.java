package com.easysubway.admin.identity.adapter.out.persistence;

import com.easysubway.admin.identity.application.port.out.AdminIdentityRepository;
import com.easysubway.admin.identity.domain.AdminIdentity;
import com.easysubway.admin.identity.domain.AdminIdentityAuthMethod;
import com.easysubway.admin.identity.domain.AdminIdentityRole;
import com.easysubway.admin.identity.domain.AdminIdentityStatus;
import com.easysubway.admin.identity.domain.AdminLoginAudit;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;

@Repository
@Profile("prod | staging | release | prod-like")
public class JdbcAdminIdentityRepository implements AdminIdentityRepository {

	private final JdbcTemplate jdbcTemplate;
	private final TransactionTemplate transactionTemplate;
	private volatile DatabaseDialect databaseDialect;

	@Autowired
	public JdbcAdminIdentityRepository(DataSource dataSource) {
		this.jdbcTemplate = new JdbcTemplate(dataSource);
		this.transactionTemplate = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
	}

	@Override
	public Optional<AdminIdentity> findByLoginId(String loginId) {
		try {
			return Optional.ofNullable(jdbcTemplate.queryForObject(
				"""
					SELECT login_id, display_name, email, password_hash, auth_method, role, status,
						failed_login_count, locked_until, password_changed_at, password_expires_at,
						credential_rotation_required, break_glass_reason, bootstrap_managed, created_at, updated_at
					FROM admin_users
					WHERE login_id = ?
					""",
				this::mapIdentity,
				normalize(loginId)
			));
		} catch (EmptyResultDataAccessException exception) {
			return Optional.empty();
		}
	}

	@Override
	public AdminIdentity save(AdminIdentity identity) {
		if (databaseDialect() == DatabaseDialect.POSTGRESQL) {
			upsertWithPostgresql(identity);
			return identity;
		}
		int updated = jdbcTemplate.update("""
			UPDATE admin_users
			SET display_name = ?,
				email = ?,
				password_hash = ?,
				auth_method = ?,
				role = ?,
				status = ?,
				failed_login_count = ?,
				locked_until = ?,
				password_changed_at = ?,
				password_expires_at = ?,
				credential_rotation_required = ?,
				break_glass_reason = ?,
				bootstrap_managed = ?,
				updated_at = ?
			WHERE login_id = ?
			""",
			identity.displayName(),
			identity.email(),
			identity.passwordHash(),
			identity.authMethod().name(),
			identity.role().name(),
			identity.status().name(),
			identity.failedLoginCount(),
			identity.lockedUntil(),
			identity.passwordChangedAt(),
			identity.passwordExpiresAt(),
			identity.credentialRotationRequired(),
			identity.breakGlassReason(),
			identity.bootstrapManaged(),
			identity.updatedAt(),
			normalize(identity.loginId())
		);
		if (updated == 0) {
			insert(identity);
		}
		return identity;
	}

	@Override
	public AdminIdentity upsertBootstrap(AdminIdentity identity) {
		var current = findByLoginId(identity.loginId());
		if (current.isPresent()) {
			return current.orElseThrow();
		}
		if (databaseDialect() == DatabaseDialect.POSTGRESQL) {
			int inserted = insertIfAbsentPostgresql(identity);
			if (inserted == 1) {
				return identity;
			}
			return findByLoginId(identity.loginId())
				.orElseThrow(() -> new IllegalStateException("관리자 identity를 찾을 수 없습니다."));
		}
		try {
			insert(identity);
			return identity;
		} catch (DuplicateKeyException exception) {
			return findByLoginId(identity.loginId()).orElseThrow(() -> exception);
		}
	}

	private void upsertWithPostgresql(AdminIdentity identity) {
		jdbcTemplate.update("""
			INSERT INTO admin_users (
				login_id, display_name, email, password_hash, auth_method, role, status,
				failed_login_count, locked_until, password_changed_at, password_expires_at,
				credential_rotation_required, break_glass_reason, bootstrap_managed, created_at, updated_at
			)
			VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
			ON CONFLICT (login_id) DO UPDATE SET
				display_name = EXCLUDED.display_name,
				email = EXCLUDED.email,
				password_hash = EXCLUDED.password_hash,
				auth_method = EXCLUDED.auth_method,
				role = EXCLUDED.role,
				status = EXCLUDED.status,
				failed_login_count = EXCLUDED.failed_login_count,
				locked_until = EXCLUDED.locked_until,
				password_changed_at = EXCLUDED.password_changed_at,
				password_expires_at = EXCLUDED.password_expires_at,
				credential_rotation_required = EXCLUDED.credential_rotation_required,
				break_glass_reason = EXCLUDED.break_glass_reason,
				bootstrap_managed = EXCLUDED.bootstrap_managed,
				updated_at = EXCLUDED.updated_at
			""",
			normalize(identity.loginId()),
			identity.displayName(),
			identity.email(),
			identity.passwordHash(),
			identity.authMethod().name(),
			identity.role().name(),
			identity.status().name(),
			identity.failedLoginCount(),
			identity.lockedUntil(),
			identity.passwordChangedAt(),
			identity.passwordExpiresAt(),
			identity.credentialRotationRequired(),
			identity.breakGlassReason(),
			identity.bootstrapManaged(),
			identity.createdAt(),
			identity.updatedAt()
		);
	}

	private int insertIfAbsentPostgresql(AdminIdentity identity) {
		return jdbcTemplate.update("""
			INSERT INTO admin_users (
				login_id, display_name, email, password_hash, auth_method, role, status,
				failed_login_count, locked_until, password_changed_at, password_expires_at,
				credential_rotation_required, break_glass_reason, bootstrap_managed, created_at, updated_at
			)
			VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
			ON CONFLICT (login_id) DO NOTHING
			""",
			normalize(identity.loginId()),
			identity.displayName(),
			identity.email(),
			identity.passwordHash(),
			identity.authMethod().name(),
			identity.role().name(),
			identity.status().name(),
			identity.failedLoginCount(),
			identity.lockedUntil(),
			identity.passwordChangedAt(),
			identity.passwordExpiresAt(),
			identity.credentialRotationRequired(),
			identity.breakGlassReason(),
			identity.bootstrapManaged(),
			identity.createdAt(),
			identity.updatedAt()
		);
	}

	private DatabaseDialect databaseDialect() {
		DatabaseDialect cached = this.databaseDialect;
		if (cached != null) {
			return cached;
		}
		DatabaseDialect detected = jdbcTemplate.execute((ConnectionCallback<DatabaseDialect>) connection -> {
			String productName = connection.getMetaData().getDatabaseProductName();
			return "H2".equalsIgnoreCase(productName) ? DatabaseDialect.H2 : DatabaseDialect.POSTGRESQL;
		});
		DatabaseDialect resolved = detected == null ? DatabaseDialect.POSTGRESQL : detected;
		this.databaseDialect = resolved;
		return resolved;
	}

	private enum DatabaseDialect {
		POSTGRESQL,
		H2
	}

	@Override
	public int disableStaleBootstrapIdentities(Set<String> activeLoginIds, LocalDateTime now) {
		List<String> active = activeLoginIds.stream()
			.map(JdbcAdminIdentityRepository::normalize)
			.toList();
		if (active.isEmpty()) {
			return jdbcTemplate.update("""
				UPDATE admin_users
				SET status = ?,
					failed_login_count = 0,
					locked_until = NULL,
					updated_at = ?
				WHERE bootstrap_managed = TRUE
					AND status <> ?
				""",
				AdminIdentityStatus.DISABLED.name(),
				now,
				AdminIdentityStatus.DISABLED.name()
			);
		}
		String placeholders = String.join(", ", java.util.Collections.nCopies(active.size(), "?"));
		List<Object> arguments = new ArrayList<>();
		arguments.add(AdminIdentityStatus.DISABLED.name());
		arguments.add(now);
		arguments.add(AdminIdentityStatus.DISABLED.name());
		arguments.addAll(active);
		String updateSql = """
			UPDATE admin_users
			SET status = ?,
				failed_login_count = 0,
				locked_until = NULL,
				updated_at = ?
			WHERE bootstrap_managed = TRUE
				AND status <> ?
				AND login_id NOT IN (__SQL_PLACEHOLDERS__)
			""".replace("__SQL_PLACEHOLDERS__", placeholders);
		return jdbcTemplate.update(updateSql, arguments.toArray());
	}

	@Override
	public AdminIdentity recordLoginFailure(
		String loginId,
		LocalDateTime now,
		int maxFailures,
		Duration lockoutDuration
	) {
		String normalizedLoginId = normalize(loginId);
		return transactionTemplate.execute(status -> {
			AdminIdentity current = findByLoginIdForUpdate(normalizedLoginId)
				.orElseThrow(() -> new IllegalStateException("관리자 identity를 찾을 수 없습니다."));
			AdminIdentity saved = current.recordFailure(now, maxFailures, lockoutDuration);
			save(saved);
			return saved;
		});
	}

	@Override
	public AdminIdentity recordLoginSuccess(String loginId, LocalDateTime now) {
		String normalizedLoginId = normalize(loginId);
		return transactionTemplate.execute(status -> {
			AdminIdentity current = findByLoginIdForUpdate(normalizedLoginId)
				.orElseThrow(() -> new IllegalStateException("관리자 identity를 찾을 수 없습니다."));
			AdminIdentity saved = current.authMethod() == AdminIdentityAuthMethod.BREAK_GLASS
				? current.recordBreakGlassSuccess(now)
				: current.recordLocalSuccess(now);
			save(saved);
			return saved;
		});
	}

	@Override
	public void recordLoginAudit(AdminLoginAudit audit) {
		jdbcTemplate.update("""
			INSERT INTO admin_login_audits (login_id, auth_method, outcome, reason, occurred_at)
			VALUES (?, ?, ?, ?, ?)
			""",
			normalize(audit.loginId()),
			audit.authMethod().name(),
			audit.outcome(),
			audit.reason(),
			audit.occurredAt()
		);
	}

	private void insert(AdminIdentity identity) {
		jdbcTemplate.update("""
			INSERT INTO admin_users (
				login_id, display_name, email, password_hash, auth_method, role, status,
				failed_login_count, locked_until, password_changed_at, password_expires_at,
				credential_rotation_required, break_glass_reason, bootstrap_managed, created_at, updated_at
			)
			VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
			""",
			normalize(identity.loginId()),
			identity.displayName(),
			identity.email(),
			identity.passwordHash(),
			identity.authMethod().name(),
			identity.role().name(),
			identity.status().name(),
			identity.failedLoginCount(),
			identity.lockedUntil(),
			identity.passwordChangedAt(),
			identity.passwordExpiresAt(),
			identity.credentialRotationRequired(),
			identity.breakGlassReason(),
			identity.bootstrapManaged(),
			identity.createdAt(),
			identity.updatedAt()
		);
	}

	private AdminIdentity mapIdentity(ResultSet resultSet, int rowNumber) throws SQLException {
		return new AdminIdentity(
			resultSet.getString("login_id"),
			resultSet.getString("display_name"),
			resultSet.getString("email"),
			resultSet.getString("password_hash"),
			AdminIdentityAuthMethod.valueOf(resultSet.getString("auth_method")),
			AdminIdentityRole.valueOf(resultSet.getString("role")),
			AdminIdentityStatus.valueOf(resultSet.getString("status")),
			resultSet.getInt("failed_login_count"),
			resultSet.getObject("locked_until", LocalDateTime.class),
			resultSet.getObject("password_changed_at", LocalDateTime.class),
			resultSet.getObject("password_expires_at", LocalDateTime.class),
			resultSet.getBoolean("credential_rotation_required"),
			resultSet.getString("break_glass_reason"),
			resultSet.getBoolean("bootstrap_managed"),
			resultSet.getObject("created_at", LocalDateTime.class),
			resultSet.getObject("updated_at", LocalDateTime.class)
		);
	}

	private Optional<AdminIdentity> findByLoginIdForUpdate(String loginId) {
		try {
			return Optional.ofNullable(jdbcTemplate.queryForObject(
				"""
					SELECT login_id, display_name, email, password_hash, auth_method, role, status,
						failed_login_count, locked_until, password_changed_at, password_expires_at,
						credential_rotation_required, break_glass_reason, bootstrap_managed, created_at, updated_at
					FROM admin_users
					WHERE login_id = ?
					FOR UPDATE
					""",
				this::mapIdentity,
				normalize(loginId)
			));
		} catch (EmptyResultDataAccessException exception) {
			return Optional.empty();
		}
	}

	private static String normalize(String loginId) {
		return loginId == null ? "" : loginId.trim().toLowerCase(Locale.ROOT);
	}
}
