package com.easysubway.admin.savedview.adapter.out.persistence;

import com.easysubway.admin.savedview.application.port.out.AdminSavedViewRepository;
import com.easysubway.admin.savedview.domain.AdminSavedView;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@Profile("prod | staging | release | prod-like")
public class JdbcAdminSavedViewRepository implements AdminSavedViewRepository {

	private final JdbcTemplate jdbcTemplate;

	@Autowired
	JdbcAdminSavedViewRepository(DataSource dataSource) {
		this(new JdbcTemplate(dataSource));
	}

	JdbcAdminSavedViewRepository(JdbcTemplate jdbcTemplate) {
		this.jdbcTemplate = jdbcTemplate;
	}

	@Override
	public List<AdminSavedView> findByOwnerAndProgram(String adminLoginId, String programId) {
		return jdbcTemplate.query(
			"""
				SELECT view_id, admin_login_id, program_id, name, query_params, is_default, created_at, updated_at
				FROM admin_saved_views
				WHERE admin_login_id = ? AND program_id = ?
				ORDER BY is_default DESC, name ASC
				""",
			this::mapSavedView,
			adminLoginId,
			programId
		);
	}

	@Override
	public Optional<AdminSavedView> findByOwnerProgramAndName(String adminLoginId, String programId, String name) {
		return jdbcTemplate.query(
			"""
				SELECT view_id, admin_login_id, program_id, name, query_params, is_default, created_at, updated_at
				FROM admin_saved_views
				WHERE admin_login_id = ? AND program_id = ? AND name = ?
				""",
			this::mapSavedView,
			adminLoginId,
			programId,
			name
		).stream().findFirst();
	}

	@Override
	public Optional<AdminSavedView> findById(String viewId) {
		return jdbcTemplate.query(
			"""
				SELECT view_id, admin_login_id, program_id, name, query_params, is_default, created_at, updated_at
				FROM admin_saved_views
				WHERE view_id = ?
				""",
			this::mapSavedView,
			viewId
		).stream().findFirst();
	}

	@Override
	public void save(AdminSavedView view) {
		// dialect 무관 upsert: 먼저 UPDATE, 없으면 INSERT.
		int updated = jdbcTemplate.update(
			"""
				UPDATE admin_saved_views
				SET admin_login_id = ?, program_id = ?, name = ?, query_params = ?, is_default = ?, updated_at = ?
				WHERE view_id = ?
				""",
			view.adminLoginId(),
			view.programId(),
			view.name(),
			view.queryParams(),
			view.isDefault(),
			view.updatedAt(),
			view.viewId()
		);
		if (updated == 0) {
			jdbcTemplate.update(
				"""
					INSERT INTO admin_saved_views
						(view_id, admin_login_id, program_id, name, query_params, is_default, created_at, updated_at)
					VALUES (?, ?, ?, ?, ?, ?, ?, ?)
					""",
				view.viewId(),
				view.adminLoginId(),
				view.programId(),
				view.name(),
				view.queryParams(),
				view.isDefault(),
				view.createdAt(),
				view.updatedAt()
			);
		}
	}

	@Override
	public void deleteById(String viewId) {
		jdbcTemplate.update("DELETE FROM admin_saved_views WHERE view_id = ?", viewId);
	}

	@Override
	public void clearDefault(String adminLoginId, String programId) {
		jdbcTemplate.update(
			"""
				UPDATE admin_saved_views
				SET is_default = FALSE
				WHERE admin_login_id = ? AND program_id = ? AND is_default = TRUE
				""",
			adminLoginId,
			programId
		);
	}

	private AdminSavedView mapSavedView(ResultSet resultSet, int rowNum) throws SQLException {
		return new AdminSavedView(
			resultSet.getString("view_id"),
			resultSet.getString("admin_login_id"),
			resultSet.getString("program_id"),
			resultSet.getString("name"),
			resultSet.getString("query_params"),
			resultSet.getBoolean("is_default"),
			resultSet.getTimestamp("created_at").toLocalDateTime(),
			resultSet.getTimestamp("updated_at").toLocalDateTime()
		);
	}
}
