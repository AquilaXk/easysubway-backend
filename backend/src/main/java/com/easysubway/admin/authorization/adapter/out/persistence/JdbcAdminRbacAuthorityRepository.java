package com.easysubway.admin.authorization.adapter.out.persistence;

import com.easysubway.admin.authorization.application.port.out.AdminRbacAuthorityRepository;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@Profile("prod")
public class JdbcAdminRbacAuthorityRepository implements AdminRbacAuthorityRepository {

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

	private static String normalize(String loginId) {
		return loginId == null ? "" : loginId.trim().toLowerCase(Locale.ROOT);
	}
}
