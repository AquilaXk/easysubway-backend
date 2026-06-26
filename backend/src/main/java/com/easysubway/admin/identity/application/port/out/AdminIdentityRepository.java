package com.easysubway.admin.identity.application.port.out;

import com.easysubway.admin.identity.domain.AdminIdentity;
import com.easysubway.admin.identity.domain.AdminLoginAudit;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.Set;

public interface AdminIdentityRepository {

	Optional<AdminIdentity> findByLoginId(String loginId);

	AdminIdentity save(AdminIdentity identity);

	AdminIdentity upsertBootstrap(AdminIdentity identity);

	int disableStaleBootstrapIdentities(Set<String> activeLoginIds, LocalDateTime now);

	AdminIdentity recordLoginFailure(String loginId, LocalDateTime now, int maxFailures, Duration lockoutDuration);

	AdminIdentity recordLoginSuccess(String loginId, LocalDateTime now);

	void recordLoginAudit(AdminLoginAudit audit);
}
