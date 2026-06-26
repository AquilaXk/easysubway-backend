package com.easysubway.admin.authorization.adapter.out.persistence;

import com.easysubway.admin.authorization.application.port.out.AdminRbacAuthorityRepository;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

@Repository
@Profile("!prod")
public class InMemoryAdminRbacAuthorityRepository implements AdminRbacAuthorityRepository {

	private final ConcurrentMap<String, Set<String>> authoritiesByLoginId = new ConcurrentHashMap<>();

	@Override
	public Set<String> findPermissionAuthorities(String loginId) {
		return authoritiesByLoginId.getOrDefault(normalize(loginId), Set.of());
	}

	public void replacePermissionAuthorities(String loginId, Set<String> authorities) {
		authoritiesByLoginId.put(normalize(loginId), Set.copyOf(authorities));
	}

	private static String normalize(String loginId) {
		return loginId == null ? "" : loginId.trim().toLowerCase(Locale.ROOT);
	}
}
