package com.easysubway.admin.authorization.adapter.out.persistence;

import com.easysubway.admin.authorization.AdminPermission;
import com.easysubway.admin.authorization.AdminRbacRole;
import com.easysubway.admin.authorization.application.port.out.AdminRbacAuthorityRepository;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.stream.Collectors;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

@Repository
@Profile("!prod & !staging & !release & !prod-like")
public class InMemoryAdminRbacAuthorityRepository implements AdminRbacAuthorityRepository {

	private static final Set<String> VALID_AUTHORITIES = Arrays.stream(AdminPermission.values())
		.map(AdminPermission::authority)
		.collect(Collectors.toUnmodifiableSet());

	// 수동 부여(replacePermissionAuthorities) 권한과 bootstrap seed role을 분리 보관해
	// 회수(revoke)가 bootstrap-seeded role만 정리하고 수동 부여는 보존하도록 한다.
	private final ConcurrentMap<String, Set<String>> manualAuthoritiesByLoginId = new ConcurrentHashMap<>();
	private final ConcurrentMap<String, Set<AdminRbacRole>> bootstrapRolesByLoginId = new ConcurrentHashMap<>();

	@Override
	public Set<String> findPermissionAuthorities(String loginId) {
		String canonicalLoginId = normalize(loginId);
		// 수동 부여(replacePermissionAuthorities)는 명시적 권한 집합을 치환하는 계약이므로,
		// 항목이 존재하면 bootstrap seed보다 우선해 그대로 반환한다(빈 집합 치환도 존중).
		Set<String> manual = manualAuthoritiesByLoginId.get(canonicalLoginId);
		if (manual != null) {
			return manual;
		}
		Set<AdminRbacRole> bootstrapRoles = bootstrapRolesByLoginId.getOrDefault(canonicalLoginId, Set.of());
		if (bootstrapRoles.isEmpty()) {
			return Set.of();
		}
		Set<String> authorities = new HashSet<>();
		for (AdminRbacRole role : bootstrapRoles) {
			role.permissions().stream()
				.map(AdminPermission::authority)
				.forEach(authorities::add);
		}
		return Set.copyOf(authorities);
	}

	public void replacePermissionAuthorities(String loginId, Set<String> authorities) {
		Set<String> assignedAuthorities = authorities == null ? Set.of() : Set.copyOf(authorities);
		if (!VALID_AUTHORITIES.containsAll(assignedAuthorities)) {
			throw new IllegalArgumentException("선언되지 않은 관리자 permission authority가 포함되어 있습니다.");
		}
		manualAuthoritiesByLoginId.put(normalize(loginId), assignedAuthorities);
	}

	@Override
	public void seedRole(String loginId, AdminRbacRole role) {
		if (role == null) {
			return;
		}
		// bootstrap 출처 role만 별도 map에 누적한다. 수동 부여(manual) 권한은 침범하지 않는다.
		bootstrapRolesByLoginId.merge(normalize(loginId), Set.of(role), (existing, added) -> {
			var union = new HashSet<>(existing);
			union.addAll(added);
			return Set.copyOf(union);
		});
	}

	@Override
	public void revokeStaleBootstrapRoles(Set<String> activeBootstrapLoginIds) {
		Set<String> active = activeBootstrapLoginIds.stream()
			.map(InMemoryAdminRbacAuthorityRepository::normalize)
			.collect(Collectors.toUnmodifiableSet());
		// active 목록에 없는 login_id의 bootstrap-seeded role만 회수한다. 수동 부여는 그대로 둔다.
		bootstrapRolesByLoginId.keySet().removeIf(loginId -> !active.contains(loginId));
	}

	private static String normalize(String loginId) {
		return loginId == null ? "" : loginId.trim().toLowerCase(Locale.ROOT);
	}
}
