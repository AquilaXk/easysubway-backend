package com.easysubway.admin.identity.adapter.out.persistence;

import com.easysubway.admin.identity.application.port.out.AdminIdentityRepository;
import com.easysubway.admin.identity.domain.AdminIdentity;
import com.easysubway.admin.identity.domain.AdminIdentityAuthMethod;
import com.easysubway.admin.identity.domain.AdminIdentityStatus;
import com.easysubway.admin.identity.domain.AdminLoginAudit;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

@Repository
@Profile("!prod")
public class InMemoryAdminIdentityRepository implements AdminIdentityRepository {

	private final ConcurrentMap<String, AdminIdentity> identitiesByLoginId = new ConcurrentHashMap<>();
	private final List<AdminLoginAudit> audits = new ArrayList<>();

	@Override
	public Optional<AdminIdentity> findByLoginId(String loginId) {
		return Optional.ofNullable(identitiesByLoginId.get(normalize(loginId)));
	}

	@Override
	public AdminIdentity save(AdminIdentity identity) {
		identitiesByLoginId.put(normalize(identity.loginId()), identity);
		return identity;
	}

	@Override
	public AdminIdentity upsertBootstrap(AdminIdentity identity) {
		return identitiesByLoginId.compute(normalize(identity.loginId()), (key, current) -> current == null ? identity : current);
	}

	@Override
	public int disableStaleBootstrapIdentities(Set<String> activeLoginIds, LocalDateTime now) {
		Set<String> active = activeLoginIds.stream()
			.map(InMemoryAdminIdentityRepository::normalize)
			.collect(java.util.stream.Collectors.toUnmodifiableSet());
		AtomicInteger disabledCount = new AtomicInteger();
		identitiesByLoginId.replaceAll((loginId, identity) -> {
			if (!identity.bootstrapManaged()
				|| active.contains(loginId)
				|| identity.status() == AdminIdentityStatus.DISABLED) {
				return identity;
			}
			disabledCount.incrementAndGet();
			return identity.disable(now);
		});
		return disabledCount.get();
	}

	@Override
	public AdminIdentity recordLoginFailure(
		String loginId,
		LocalDateTime now,
		int maxFailures,
		Duration lockoutDuration
	) {
		var saved = new AtomicReference<AdminIdentity>();
		identitiesByLoginId.compute(normalize(loginId), (key, current) -> {
			if (current == null) {
				throw new IllegalStateException("관리자 identity를 찾을 수 없습니다.");
			}
			AdminIdentity next = current.recordFailure(now, maxFailures, lockoutDuration);
			saved.set(next);
			return next;
		});
		return saved.get();
	}

	@Override
	public AdminIdentity recordLoginSuccess(String loginId, LocalDateTime now) {
		var saved = new AtomicReference<AdminIdentity>();
		identitiesByLoginId.compute(normalize(loginId), (key, current) -> {
			if (current == null) {
				throw new IllegalStateException("관리자 identity를 찾을 수 없습니다.");
			}
			AdminIdentity next = current.authMethod() == AdminIdentityAuthMethod.BREAK_GLASS
				? current.recordBreakGlassSuccess(now)
				: current.recordLocalSuccess(now);
			saved.set(next);
			return next;
		});
		return saved.get();
	}

	@Override
	public synchronized void recordLoginAudit(AdminLoginAudit audit) {
		audits.add(audit);
	}

	public synchronized List<AdminLoginAudit> audits() {
		return List.copyOf(audits);
	}

	private static String normalize(String loginId) {
		return loginId == null ? "" : loginId.trim().toLowerCase(Locale.ROOT);
	}
}
