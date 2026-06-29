package com.easysubway.admin.code.adapter.out.persistence;

import com.easysubway.admin.code.application.port.out.AdminCommonCodeRepository;
import com.easysubway.admin.code.domain.AdminCommonCode;
import com.easysubway.admin.code.domain.AdminCommonCodeGroup;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@Profile("prod | staging | release | prod-like")
public class JdbcAdminCommonCodeRepository implements AdminCommonCodeRepository {

	private final JdbcTemplate jdbcTemplate;

	@Autowired
	public JdbcAdminCommonCodeRepository(DataSource dataSource) {
		this.jdbcTemplate = new JdbcTemplate(dataSource);
	}

	@Override
	public List<AdminCommonCodeGroup> findGroups() {
		return jdbcTemplate.query("""
			SELECT group_code, display_name, description, sort_order, enabled, created_at, updated_at
			FROM admin_common_code_groups
			ORDER BY sort_order ASC, group_code ASC
			""", this::mapGroup);
	}

	@Override
	public Optional<AdminCommonCodeGroup> findGroup(String groupCode) {
		try {
			return Optional.ofNullable(jdbcTemplate.queryForObject("""
				SELECT group_code, display_name, description, sort_order, enabled, created_at, updated_at
				FROM admin_common_code_groups
				WHERE group_code = ?
				""", this::mapGroup, groupCode));
		} catch (EmptyResultDataAccessException exception) {
			return Optional.empty();
		}
	}

	@Override
	public List<AdminCommonCode> findCodes(String groupCode) {
		return jdbcTemplate.query("""
			SELECT group_code, code, display_name, description, sort_order, enabled, created_at, updated_at
			FROM admin_common_codes
			WHERE group_code = ?
			ORDER BY sort_order ASC, code ASC
			""", this::mapCode, groupCode);
	}

	@Override
	public Optional<AdminCommonCode> findCode(String groupCode, String code) {
		try {
			return Optional.ofNullable(jdbcTemplate.queryForObject("""
				SELECT group_code, code, display_name, description, sort_order, enabled, created_at, updated_at
				FROM admin_common_codes
				WHERE group_code = ? AND code = ?
				""", this::mapCode, groupCode, code));
		} catch (EmptyResultDataAccessException exception) {
			return Optional.empty();
		}
	}

	@Override
	public AdminCommonCode saveCode(AdminCommonCode code) {
		if (updateCode(code) == 0) {
			try {
				insertCode(code);
			} catch (DuplicateKeyException exception) {
				updateCode(code);
			}
		}
		return code;
	}

	private int updateCode(AdminCommonCode code) {
		return jdbcTemplate.update("""
			UPDATE admin_common_codes
			SET display_name = ?, description = ?, sort_order = ?, enabled = ?, updated_at = ?
			WHERE group_code = ? AND code = ?
			""",
			code.displayName(),
			code.description(),
			code.sortOrder(),
			code.enabled(),
			code.updatedAt(),
			code.groupCode(),
			code.code()
		);
	}

	private void insertCode(AdminCommonCode code) {
		jdbcTemplate.update("""
			INSERT INTO admin_common_codes (
				group_code, code, display_name, description, sort_order, enabled, created_at, updated_at
			)
			VALUES (?, ?, ?, ?, ?, ?, ?, ?)
			""",
			code.groupCode(),
			code.code(),
			code.displayName(),
			code.description(),
			code.sortOrder(),
			code.enabled(),
			code.createdAt(),
			code.updatedAt()
		);
	}

	private AdminCommonCodeGroup mapGroup(ResultSet resultSet, int rowNumber) throws SQLException {
		return new AdminCommonCodeGroup(
			resultSet.getString("group_code"),
			resultSet.getString("display_name"),
			resultSet.getString("description"),
			resultSet.getInt("sort_order"),
			resultSet.getBoolean("enabled"),
			resultSet.getTimestamp("created_at").toLocalDateTime(),
			resultSet.getTimestamp("updated_at").toLocalDateTime()
		);
	}

	private AdminCommonCode mapCode(ResultSet resultSet, int rowNumber) throws SQLException {
		return new AdminCommonCode(
			resultSet.getString("group_code"),
			resultSet.getString("code"),
			resultSet.getString("display_name"),
			resultSet.getString("description"),
			resultSet.getInt("sort_order"),
			resultSet.getBoolean("enabled"),
			resultSet.getTimestamp("created_at").toLocalDateTime(),
			resultSet.getTimestamp("updated_at").toLocalDateTime()
		);
	}
}
