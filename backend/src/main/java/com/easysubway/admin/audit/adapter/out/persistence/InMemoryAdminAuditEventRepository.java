package com.easysubway.admin.audit.adapter.out.persistence;

import com.easysubway.admin.audit.application.port.out.AdminAuditEventRepository;
import com.easysubway.admin.audit.domain.AdminAuditEvent;
import com.easysubway.admin.audit.domain.AdminAuditEventType;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
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
}
