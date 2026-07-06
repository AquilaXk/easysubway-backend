package com.easysubway.admin.audit.adapter.out.persistence;

import com.easysubway.admin.audit.application.AdminAuditActorContext;
import com.easysubway.admin.audit.application.AdminAuditQuery;
import com.easysubway.admin.audit.application.port.out.AdminAuditEventRepository;
import com.easysubway.admin.audit.domain.AdminAuditEvent;
import com.easysubway.admin.audit.domain.AdminAuditEventType;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

@Repository
@Profile("!prod & !staging & !release & !prod-like")
public class InMemoryAdminAuditEventRepository implements AdminAuditEventRepository {

	private final AtomicLong sequence = new AtomicLong();
	private final List<AdminAuditEvent> events = new ArrayList<>();

	@Override
	public synchronized void save(AdminAuditEvent event) {
		events.add(new AdminAuditEvent(
			sequence.incrementAndGet(),
			event.eventType(),
			event.actor(),
			event.rolePermission(),
			event.requestId(),
			event.clientIp(),
			event.userAgent(),
			event.targetType(),
			event.targetId(),
			event.action(),
			event.outcome(),
			event.reason(),
			event.occurredAt()
		));
	}

	@Override
	public synchronized List<AdminAuditEvent> findRecent(AdminAuditEventType eventType, int limit) {
		return findRecent(eventType, limit, 0);
	}

	@Override
	public synchronized List<AdminAuditEvent> findRecent(AdminAuditEventType eventType, int limit, int offset) {
		List<AdminAuditEvent> recent = new ArrayList<>();
		int skipped = 0;
		for (int index = events.size() - 1; index >= 0 && recent.size() < Math.max(0, limit); index--) {
			AdminAuditEvent event = events.get(index);
			if (eventType == null || event.eventType() == eventType) {
				if (skipped++ < Math.max(offset, 0)) {
					continue;
				}
				recent.add(event);
			}
		}
		return List.copyOf(recent);
	}

	@Override
	public synchronized List<AdminAuditEvent> search(AdminAuditQuery query) {
		List<AdminAuditEvent> matched = matching(query)
			.sorted(Comparator
				.comparing(AdminAuditEvent::occurredAt)
				.thenComparing(AdminAuditEvent::id)
				.reversed())
			.toList();
		int fromIndex = Math.min(query.offset(), matched.size());
		int toIndex = Math.min(fromIndex + query.size(), matched.size());
		return List.copyOf(matched.subList(fromIndex, toIndex));
	}

	@Override
	public synchronized long count(AdminAuditQuery query) {
		return matching(query).count();
	}

	@Override
	public synchronized List<AdminAuditEvent> findForExport(AdminAuditQuery query, int limit) {
		return matching(query)
			.sorted(Comparator
				.comparing(AdminAuditEvent::occurredAt)
				.thenComparing(AdminAuditEvent::id)
				.reversed())
			.limit(Math.max(limit, 0))
			.toList();
	}

	@Override
	public synchronized List<String> findDistinctActors(AdminAuditEventType scopeEventType) {
		return events.stream()
			.filter(event -> scopeEventType == null || event.eventType() == scopeEventType)
			.map(AdminAuditEvent::actor)
			.distinct()
			.sorted()
			.toList();
	}

	@Override
	public synchronized Optional<AdminAuditEvent> findById(
		long id, AdminAuditEventType scopeEventType, boolean excludePrivacyRead) {
		return events.stream()
			.filter(event -> event.id() != null && event.id() == id)
			.filter(event -> scopeEventType == null || event.eventType() == scopeEventType)
			.filter(event -> !excludePrivacyRead || event.eventType() != AdminAuditEventType.PRIVACY_READ)
			.findFirst();
	}

	@Override
	public synchronized AdminAuditActorContext findActorContext(
		AdminAuditEvent pivot, AdminAuditEventType scopeEventType, boolean excludePrivacyRead, int radius) {
		if (radius <= 0) {
			return AdminAuditActorContext.empty();
		}
		// pivot 기준 (occurred_at, id) 튜플 비교로 같은 actor의 직전·직후를 시간 오름차순으로 나눈다.
		Comparator<AdminAuditEvent> chronological = Comparator
			.comparing(AdminAuditEvent::occurredAt)
			.thenComparing(AdminAuditEvent::id);
		List<AdminAuditEvent> sameActor = events.stream()
			.filter(event -> event.actor().equals(pivot.actor()))
			.filter(event -> scopeEventType == null || event.eventType() == scopeEventType)
			.filter(event -> !excludePrivacyRead || event.eventType() != AdminAuditEventType.PRIVACY_READ)
			.filter(event -> !event.id().equals(pivot.id()))
			.sorted(chronological)
			.toList();
		List<AdminAuditEvent> before = sameActor.stream()
			.filter(event -> chronological.compare(event, pivot) < 0)
			.toList();
		List<AdminAuditEvent> after = sameActor.stream()
			.filter(event -> chronological.compare(event, pivot) > 0)
			.limit(radius)
			.toList();
		// 직전은 pivot에 가까운 radius개만 남기되(뒤쪽), 표시는 시간 오름차순 유지.
		List<AdminAuditEvent> nearestBefore = before.subList(Math.max(0, before.size() - radius), before.size());
		return new AdminAuditActorContext(List.copyOf(nearestBefore), after);
	}

	private Stream<AdminAuditEvent> matching(AdminAuditQuery query) {
		return events.stream().filter(event -> matches(event, query));
	}

	private boolean matches(AdminAuditEvent event, AdminAuditQuery query) {
		if (query.hasEventType() && event.eventType() != query.eventType()) {
			return false;
		}
		if (query.hasActor() && !query.actor().equals(event.actor())) {
			return false;
		}
		if (query.hasOutcome() && event.outcome() != query.outcome()) {
			return false;
		}
		if (query.hasTargetKeyword() && !matchesTarget(event, query.targetKeyword())) {
			return false;
		}
		if (query.reasonMissing() && event.reason() != null) {
			return false;
		}
		if (query.excludePrivacyRead() && event.eventType() == AdminAuditEventType.PRIVACY_READ) {
			return false;
		}
		LocalDateTime occurredAt = event.occurredAt();
		if (query.occurredFrom() != null && occurredAt.isBefore(query.occurredFrom().atStartOfDay())) {
			return false;
		}
		return query.occurredTo() == null
			|| occurredAt.isBefore(query.occurredTo().plusDays(1).atStartOfDay());
	}

	private static boolean matchesTarget(AdminAuditEvent event, String keyword) {
		String needle = keyword.toLowerCase(Locale.ROOT);
		String targetId = event.targetId() == null ? "" : event.targetId().toLowerCase(Locale.ROOT);
		String targetType = event.targetType() == null ? "" : event.targetType().toLowerCase(Locale.ROOT);
		return targetId.contains(needle) || targetType.contains(needle);
	}
}
