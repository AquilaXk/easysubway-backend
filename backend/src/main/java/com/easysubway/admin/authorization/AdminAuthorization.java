package com.easysubway.admin.authorization;

import com.easysubway.admin.identity.domain.AdminIdentityRole;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.security.core.Authentication;

public final class AdminAuthorization {

	private static final String ADMIN_ROLE_AUTHORITY = "ROLE_ADMIN";

	private AdminAuthorization() {
	}

	public static Set<String> authoritiesFor(AdminIdentityRole identityRole) {
		if (identityRole == AdminIdentityRole.OPERATOR_ADMIN) {
			return Set.of();
		}
		AdminRbacRole role = AdminRbacRole.SUPER_ADMIN;
		return role.permissions().stream()
			.map(AdminPermission::authority)
			.collect(Collectors.toUnmodifiableSet());
	}

	public static boolean hasPermission(Authentication authentication, AdminPermission permission) {
		if (authentication == null || permission == null) {
			return false;
		}
		return authentication.getAuthorities().stream()
			.anyMatch(authority -> permission.authority().equals(authority.getAuthority())
				|| ADMIN_ROLE_AUTHORITY.equals(authority.getAuthority()));
	}
}
