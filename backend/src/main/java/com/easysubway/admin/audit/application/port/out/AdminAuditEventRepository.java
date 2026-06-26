package com.easysubway.admin.audit.application.port.out;

import com.easysubway.admin.audit.domain.AdminAuditEvent;
import com.easysubway.admin.audit.domain.AdminAuditEventType;
import java.util.List;

public interface AdminAuditEventRepository {

	void save(AdminAuditEvent event);

	List<AdminAuditEvent> findRecent(AdminAuditEventType eventType, int limit);
}
