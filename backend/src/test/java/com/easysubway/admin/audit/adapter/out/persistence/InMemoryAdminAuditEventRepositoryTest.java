package com.easysubway.admin.audit.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.easysubway.admin.audit.application.AdminAuditActorContext;
import com.easysubway.admin.audit.application.AdminAuditQuery;
import com.easysubway.admin.audit.domain.AdminAuditEvent;
import com.easysubway.admin.audit.domain.AdminAuditEventType;
import com.easysubway.admin.audit.domain.AdminAuditOutcome;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("인메모리 관리자 감사 이벤트 저장소")
class InMemoryAdminAuditEventRepositoryTest {

	private final InMemoryAdminAuditEventRepository repository = new InMemoryAdminAuditEventRepository();

	@Test
	@DisplayName("감사 검색은 유형·actor·결과·target·사유없음으로 필터하고 발생 최신순으로 정렬한다")
	void searchFiltersAuditEvents() {
		LocalDateTime base = LocalDateTime.of(2026, 6, 27, 9, 0);
		repository.save(event(AdminAuditEventType.ADMIN_ACTION, "admin-a", AdminAuditOutcome.SUCCESS,
			"REPORT", "report-1", "업무 맥락", base));
		repository.save(event(AdminAuditEventType.PRIVACY_READ, "admin-b", AdminAuditOutcome.SUCCESS,
			"REPORT", "report-2", null, base.plusMinutes(1)));
		repository.save(event(AdminAuditEventType.ADMIN_ACTION, "admin-a", AdminAuditOutcome.FAILURE,
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
	@DisplayName("감사 검색은 페이지 크기·오프셋으로 잘라낸다")
	void searchPaginates() {
		LocalDateTime base = LocalDateTime.of(2026, 6, 27, 9, 0);
		for (int index = 0; index < 5; index++) {
			repository.save(event(AdminAuditEventType.ADMIN_ACTION, "admin-a", AdminAuditOutcome.SUCCESS,
				"REPORT", "report-" + index, "업무 맥락", base.plusMinutes(index)));
		}

		assertThat(repository.search(new AdminAuditQuery(null, null, null, null, null, null, false, 0, 2)))
			.extracting(AdminAuditEvent::targetId).containsExactly("report-4", "report-3");
		assertThat(repository.search(new AdminAuditQuery(null, null, null, null, null, null, false, 2, 2)))
			.extracting(AdminAuditEvent::targetId).containsExactly("report-0");
	}

	@Test
	@DisplayName("단건 조회는 scope 유형이 지정되면 그 유형만 돌려준다(권한 분리)")
	void findByIdRespectsScope() {
		LocalDateTime base = LocalDateTime.of(2026, 6, 27, 9, 0);
		repository.save(event(AdminAuditEventType.ADMIN_ACTION, "admin-a", AdminAuditOutcome.SUCCESS,
			"REPORT", "report-1", "업무 맥락", base));
		AdminAuditEvent stored = repository.search(
			new AdminAuditQuery(null, null, null, null, null, null, false, 0, 10)).get(0);

		assertThat(repository.findById(stored.id(), null, false)).isPresent();
		assertThat(repository.findById(stored.id(), AdminAuditEventType.PRIVACY_READ, false)).isEmpty();
		assertThat(repository.findById(999L, null, false)).isEmpty();
	}

	@Test
	@DisplayName("관리자 감사 화면(excludePrivacyRead)은 PRIVACY_READ를 목록·단건·타임라인에서 제외한다")
	void excludePrivacyReadPartitionsGeneralScreen() {
		LocalDateTime base = LocalDateTime.of(2026, 6, 27, 9, 0);
		repository.save(event(AdminAuditEventType.ADMIN_ACTION, "alice", AdminAuditOutcome.SUCCESS,
			"REPORT", "admin-1", "업무", base));
		repository.save(event(AdminAuditEventType.PRIVACY_READ, "alice", AdminAuditOutcome.SUCCESS,
			"REPORT", "priv-1", "업무", base.plusMinutes(1)));
		AdminAuditEvent privacyEvent = repository.search(
				new AdminAuditQuery(null, "alice", null, null, null, null, false, 0, 10))
			.stream().filter(e -> e.targetId().equals("priv-1")).findFirst().orElseThrow();

		// 일반 화면 질의는 PRIVACY_READ를 제외한다.
		AdminAuditQuery generalQuery =
			new AdminAuditQuery(null, "alice", null, null, null, null, false, 0, 10, true);
		assertThat(repository.search(generalQuery)).extracting(AdminAuditEvent::targetId).containsExactly("admin-1");
		assertThat(repository.count(generalQuery)).isEqualTo(1);
		// 일반 화면 단건은 PRIVACY_READ를 열 수 없다.
		assertThat(repository.findById(privacyEvent.id(), null, true)).isEmpty();
	}

	@Test
	@DisplayName("actor 전후 타임라인은 같은 actor의 직전·직후를 radius개씩 시간순으로 준다")
	void findActorContextSplitsBeforeAndAfter() {
		LocalDateTime base = LocalDateTime.of(2026, 6, 27, 9, 0);
		for (int index = 0; index < 6; index++) {
			repository.save(event(AdminAuditEventType.ADMIN_ACTION, "admin-a", AdminAuditOutcome.SUCCESS,
				"REPORT", "a-" + index, "업무 맥락", base.plusMinutes(index)));
		}
		// 다른 actor 이벤트는 타임라인에서 제외되어야 한다.
		repository.save(event(AdminAuditEventType.ADMIN_ACTION, "admin-b", AdminAuditOutcome.SUCCESS,
			"REPORT", "b-0", "업무 맥락", base.plusSeconds(150)));
		List<AdminAuditEvent> all = repository.search(
			new AdminAuditQuery(null, "admin-a", null, null, null, null, false, 0, 10));
		AdminAuditEvent pivot = all.stream().filter(event -> event.targetId().equals("a-3")).findFirst().orElseThrow();

		AdminAuditActorContext context = repository.findActorContext(pivot, null, false, 2);

		assertThat(context.before()).extracting(AdminAuditEvent::targetId).containsExactly("a-1", "a-2");
		assertThat(context.after()).extracting(AdminAuditEvent::targetId).containsExactly("a-4", "a-5");
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

	private AdminAuditEvent event(
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
}
