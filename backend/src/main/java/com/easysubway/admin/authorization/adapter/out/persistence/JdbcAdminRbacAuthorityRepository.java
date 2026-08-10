package com.easysubway.admin.authorization.adapter.out.persistence;

import com.easysubway.admin.authorization.AdminRbacRole;
import com.easysubway.admin.authorization.application.port.out.AdminRbacAuthorityRepository;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@Profile("prod | staging | release | prod-like")
public class JdbcAdminRbacAuthorityRepository implements AdminRbacAuthorityRepository {

	// bootstrap seed가 만든 role 할당임을 표시하는 provenance 값. 회수(revoke) 시 이 값의
	// 행만 대상으로 삼고, 수동 부여(granted_by NULL 등)는 건드리지 않는다.
	private static final String BOOTSTRAP_PROVENANCE = "bootstrap";

	private final JdbcTemplate jdbcTemplate;

	@Autowired
	public JdbcAdminRbacAuthorityRepository(DataSource dataSource) {
		this.jdbcTemplate = new JdbcTemplate(dataSource);
	}

	@Override
	public Set<String> findPermissionAuthorities(String loginId) {
		return jdbcTemplate.queryForList("""
			SELECT DISTINCT rp.permission_code
			FROM admin_user_roles ur
			JOIN admin_role_permissions rp ON rp.role_code = ur.role_code
			WHERE ur.login_id = ?
			""", String.class, normalize(loginId))
			.stream()
			.collect(Collectors.toUnmodifiableSet());
	}

	@Override
	public void seedRole(String loginId, AdminRbacRole role) {
		if (role == null) {
			return;
		}
		String canonicalLoginId = normalize(loginId);
		Integer existing = jdbcTemplate.queryForObject("""
			SELECT COUNT(*)
			FROM admin_user_roles
			WHERE login_id = ? AND role_code = ?
			""", Integer.class, canonicalLoginId, role.name());
		// 이미 동일 (login_id, role_code) 행이 있으면 provenance와 무관하게 덮지 않는다.
		// 운영자가 수동 부여(granted_by NULL)한 role을 bootstrap seed가 침범하지 않도록 존중한다.
		if (existing != null && existing > 0) {
			return;
		}
		try {
			jdbcTemplate.update("""
				INSERT INTO admin_user_roles (created_at, role_code, login_id, granted_by)
				VALUES (?, ?, ?, ?)
				""", LocalDateTime.now(Clock.systemUTC()), role.name(), canonicalLoginId, BOOTSTRAP_PROVENANCE);
		} catch (DuplicateKeyException exception) {
			// 동시 부팅이 이미 동일 role 할당을 seed한 경우이므로 멱등하게 무시한다.
		}
	}

	@Override
	public void revokeStaleBootstrapRoles(Set<String> activeBootstrapLoginIds) {
		List<String> active = activeBootstrapLoginIds.stream()
			.map(JdbcAdminRbacAuthorityRepository::normalize)
			.toList();
		if (active.isEmpty()) {
			jdbcTemplate.update("""
				DELETE FROM admin_user_roles
				WHERE granted_by = ?
				""", BOOTSTRAP_PROVENANCE);
			return;
		}
		String placeholders = String.join(", ", Collections.nCopies(active.size(), "?"));
		List<Object> arguments = new ArrayList<>();
		arguments.add(BOOTSTRAP_PROVENANCE);
		arguments.addAll(active);
		String deleteSql = """
			DELETE FROM admin_user_roles
			WHERE granted_by = ?
				AND login_id NOT IN (__SQL_PLACEHOLDERS__)
			""".replace("__SQL_PLACEHOLDERS__", placeholders);
		jdbcTemplate.update(deleteSql, arguments.toArray());
	}

	private static String normalize(String loginId) {
		return loginId == null ? "" : loginId.trim().toLowerCase(Locale.ROOT);
	}
}
