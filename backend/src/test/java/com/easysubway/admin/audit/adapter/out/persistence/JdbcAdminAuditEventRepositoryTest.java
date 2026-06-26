package com.easysubway.admin.audit.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.easysubway.admin.audit.domain.AdminAuditEvent;
import com.easysubway.admin.audit.domain.AdminAuditEventType;
import com.easysubway.admin.audit.domain.AdminAuditOutcome;
import java.time.LocalDateTime;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

@DisplayName("JDBC 관리자 감사 이벤트 저장소")
class JdbcAdminAuditEventRepositoryTest {

	@Test
	@DisplayName("상태 변경과 개인정보 조회 감사 이벤트를 저장하고 최근순으로 조회한다")
	void saveAndListAuditEvents() {
		var dataSource = adminAuditDataSource();
		var repository = new JdbcAdminAuditEventRepository(dataSource);
		LocalDateTime now = LocalDateTime.of(2026, 6, 27, 0, 0);

		repository.save(event(AdminAuditEventType.ADMIN_ACTION, "POST /admin/reports/{reportId}/page/review", now));
		repository.save(event(AdminAuditEventType.PRIVACY_READ, "VIEW_REPORT_DETAIL", now.plusMinutes(1)));

		assertThat(repository.findRecent(null, 10))
			.extracting(AdminAuditEvent::eventType)
			.containsExactly(AdminAuditEventType.PRIVACY_READ, AdminAuditEventType.ADMIN_ACTION);
		assertThat(repository.findRecent(AdminAuditEventType.PRIVACY_READ, 10))
			.singleElement()
			.satisfies(event -> {
				assertThat(event.actor()).isEqualTo("admin-user");
				assertThat(event.targetId()).isEqualTo("report-1");
				assertThat(event.reason()).contains("업무 맥락");
			});
	}

	@Test
	@DisplayName("감사 이벤트 free-text에는 민감정보를 저장하지 않는다")
	void auditEventRejectsSensitiveFreeText() {
		assertThatThrownBy(() -> new AdminAuditEvent(
			null,
			AdminAuditEventType.PRIVACY_READ,
			"admin-user",
			"admin.privacy-log.read",
			"request-1",
			"127.0.0.1",
			"JUnit",
			"FACILITY_REPORT",
			"report-1",
			"VIEW_REPORT_DETAIL",
			AdminAuditOutcome.SUCCESS,
			"privateNote=원문",
			LocalDateTime.of(2026, 6, 27, 0, 0)
		))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("민감정보");
	}

	@Test
	@DisplayName("감사 이벤트 테이블은 민감 원문 컬럼을 갖지 않는다")
	void auditEventSchemaDoesNotHaveSensitiveColumns() {
		var dataSource = adminAuditDataSource();
		var columns = new JdbcTemplate(dataSource).queryForList("""
			SELECT LOWER(column_name)
			FROM information_schema.columns
			WHERE LOWER(table_name) = 'admin_audit_events'
			""", String.class);

		assertThat(columns)
			.doesNotContain("receipt_token", "upload_url", "photo_object_key", "private_note", "secret", "provider_key");
	}

	private AdminAuditEvent event(AdminAuditEventType type, String action, LocalDateTime occurredAt) {
		return new AdminAuditEvent(
			null,
			type,
			"admin-user",
			"admin.view,admin.audit.read",
			"request-1",
			"127.0.0.1",
			"JUnit",
			"FACILITY_REPORT",
			"report-1",
			action,
			AdminAuditOutcome.SUCCESS,
			"업무 맥락: 신고 상세 조회",
			occurredAt
		);
	}

	private DataSource adminAuditDataSource() {
		var dataSource = new EmbeddedDatabaseBuilder()
			.setType(EmbeddedDatabaseType.H2)
			.generateUniqueName(true)
			.build();
		new ResourceDatabasePopulator(
			new ClassPathResource("db/migration/h2/V10__admin_rbac_menu.sql"),
			new ClassPathResource("db/migration/h2/V11__admin_audit_events.sql")
		).execute(dataSource);
		return dataSource;
	}
}
