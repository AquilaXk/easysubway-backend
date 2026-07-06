package com.easysubway.admin.audit.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.easysubway.admin.audit.application.AdminAuditQuery;
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

	@Test
	@DisplayName("감사 검색은 유형·actor·결과·target·사유없음으로 필터하고 발생 최신순으로 정렬한다")
	void searchFiltersAuditEvents() {
		var repository = new JdbcAdminAuditEventRepository(adminAuditDataSource());
		LocalDateTime base = LocalDateTime.of(2026, 6, 27, 9, 0);
		repository.save(fullEvent(AdminAuditEventType.ADMIN_ACTION, "admin-a", AdminAuditOutcome.SUCCESS,
			"REPORT", "report-1", "업무 맥락", base));
		repository.save(fullEvent(AdminAuditEventType.PRIVACY_READ, "admin-b", AdminAuditOutcome.SUCCESS,
			"REPORT", "report-2", null, base.plusMinutes(1)));
		repository.save(fullEvent(AdminAuditEventType.ADMIN_ACTION, "admin-a", AdminAuditOutcome.FAILURE,
			"INCIDENT", "incident-9", "업무 맥락", base.plusMinutes(2)));

		assertThat(repository.search(query(AdminAuditEventType.ADMIN_ACTION, "admin-a", null, null, false)))
			.extracting(AdminAuditEvent::targetId).containsExactly("incident-9", "report-1");
		assertThat(repository.search(query(null, null, AdminAuditOutcome.FAILURE, null, false)))
			.extracting(AdminAuditEvent::targetId).containsExactly("incident-9");
		assertThat(repository.search(query(null, null, null, "report", false)))
			.extracting(AdminAuditEvent::targetId).containsExactly("report-2", "report-1");
		assertThat(repository.search(query(null, null, null, null, true)))
			.extracting(AdminAuditEvent::targetId).containsExactly("report-2");
		assertThat(repository.count(query(AdminAuditEventType.ADMIN_ACTION, null, null, null, false))).isEqualTo(2);
		assertThat(repository.findDistinctActors(null)).containsExactly("admin-a", "admin-b");
		assertThat(repository.findDistinctActors(AdminAuditEventType.PRIVACY_READ)).containsExactly("admin-b");
	}

	@Test
	@DisplayName("단건 조회와 actor 전후 타임라인은 scope 유형을 지키고 전후를 시간순으로 나눈다")
	void findByIdAndActorContext() {
		var repository = new JdbcAdminAuditEventRepository(adminAuditDataSource());
		LocalDateTime base = LocalDateTime.of(2026, 6, 27, 9, 0);
		for (int index = 0; index < 6; index++) {
			repository.save(fullEvent(AdminAuditEventType.ADMIN_ACTION, "admin-a", AdminAuditOutcome.SUCCESS,
				"REPORT", "a-" + index, "업무 맥락", base.plusMinutes(index)));
		}
		repository.save(fullEvent(AdminAuditEventType.PRIVACY_READ, "admin-a", AdminAuditOutcome.SUCCESS,
			"REPORT", "priv-0", "업무 맥락", base.plusSeconds(200)));

		AdminAuditEvent pivot = repository.search(
				new AdminAuditQuery(null, "admin-a", null, "a-3", null, null, false, 0, 10))
			.get(0);

		assertThat(repository.findById(pivot.id(), null, false)).isPresent();
		assertThat(repository.findById(pivot.id(), AdminAuditEventType.PRIVACY_READ, false)).isEmpty();

		var context = repository.findActorContext(pivot, AdminAuditEventType.ADMIN_ACTION, false, 2);
		assertThat(context.before()).extracting(AdminAuditEvent::targetId).containsExactly("a-1", "a-2");
		assertThat(context.after()).extracting(AdminAuditEvent::targetId).containsExactly("a-4", "a-5");
	}

	@Test
	@DisplayName("target 검색은 LIKE 메타문자(%,_)를 리터럴로 이스케이프해 오매칭을 막는다")
	void searchEscapesLikeMetacharacters() {
		var repository = new JdbcAdminAuditEventRepository(adminAuditDataSource());
		LocalDateTime base = LocalDateTime.of(2026, 6, 27, 9, 0);
		repository.save(fullEvent(AdminAuditEventType.ADMIN_ACTION, "admin-a", AdminAuditOutcome.SUCCESS,
			"REPORT", "a%b", "업무 맥락", base));
		repository.save(fullEvent(AdminAuditEventType.ADMIN_ACTION, "admin-a", AdminAuditOutcome.SUCCESS,
			"REPORT", "axb", "업무 맥락", base.plusMinutes(1)));

		// '%'가 와일드카드로 해석되면 axb도 매칭되지만, 이스케이프되어 a%b만 매칭돼야 한다.
		assertThat(repository.search(query(null, null, null, "a%b", false)))
			.extracting(AdminAuditEvent::targetId).containsExactly("a%b");
	}

	private static AdminAuditQuery query(
		AdminAuditEventType eventType,
		String actor,
		AdminAuditOutcome outcome,
		String targetKeyword,
		boolean reasonMissing
	) {
		return AdminAuditQuery.of(
			null, eventType, actor, outcome, targetKeyword, null, null, reasonMissing, null, null, false);
	}

	private AdminAuditEvent fullEvent(
		AdminAuditEventType type,
		String actor,
		AdminAuditOutcome outcome,
		String targetType,
		String targetId,
		String reason,
		LocalDateTime occurredAt
	) {
		return new AdminAuditEvent(
			null, type, actor, "admin.view", "request-1", "127.0.0.1", "JUnit",
			targetType, targetId, "ACTION", outcome, reason, occurredAt);
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
