package com.easysubway.admin.authorization.adapter.out.persistence;

import com.easysubway.admin.authorization.AdminPermission;
import com.easysubway.admin.authorization.application.port.out.AdminRbacAuthorityRepository;
import java.util.Arrays;
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

	private final ConcurrentMap<String, Set<String>> authoritiesByLoginId = new ConcurrentHashMap<>();

	@Override
	public Set<String> findPermissionAuthorities(String loginId) {
		return authoritiesByLoginId.getOrDefault(normalize(loginId), Set.of());
	}

	public void replacePermissionAuthorities(String loginId, Set<String> authorities) {
		Set<String> assignedAuthorities = authorities == null ? Set.of() : Set.copyOf(authorities);
		if (!VALID_AUTHORITIES.containsAll(assignedAuthorities)) {
			throw new IllegalArgumentException("선언되지 않은 관리자 permission authority가 포함되어 있습니다.");
		}
		authoritiesByLoginId.put(normalize(loginId), assignedAuthorities);
	}

	private static String normalize(String loginId) {
		return loginId == null ? "" : loginId.trim().toLowerCase(Locale.ROOT);
	}
}
