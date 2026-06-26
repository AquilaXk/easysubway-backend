package com.easysubway.admin.authorization;

import com.easysubway.admin.identity.domain.AdminIdentityRole;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.security.core.Authentication;

public final class AdminAuthorization {

	private AdminAuthorization() {
	}

	public static Set<String> authoritiesFor(AdminIdentityRole identityRole) {
		return authoritiesFor(identityRole, Set.of());
	}

	public static Set<String> authoritiesFor(AdminIdentityRole identityRole, Set<String> assignedAuthorities) {
		if (identityRole == AdminIdentityRole.OPERATOR_ADMIN) {
			return Set.of();
		}
		return assignedAuthorities == null ? Set.of() : Set.copyOf(assignedAuthorities);
	}

	public static Set<String> superAdminAuthorities() {
		return AdminRbacRole.SUPER_ADMIN.permissions().stream()
			.map(AdminPermission::authority)
			.collect(Collectors.toUnmodifiableSet());
	}

	public static boolean hasPermission(Authentication authentication, AdminPermission permission) {
		if (authentication == null || permission == null) {
			return false;
		}
		return authentication.getAuthorities().stream()
			.anyMatch(authority -> permission.authority().equals(authority.getAuthority()));
	}
}
