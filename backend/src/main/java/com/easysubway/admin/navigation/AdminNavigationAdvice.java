package com.easysubway.admin.navigation;

import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.core.env.Environment;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

@ControllerAdvice
class AdminNavigationAdvice {

	private final Environment environment;

	AdminNavigationAdvice(Environment environment) {
		this.environment = environment;
	}

	@ModelAttribute("adminProgramIds")
	Set<String> adminProgramIds(Authentication authentication) {
		return AdminProgram.visibleTo(authentication).stream()
			.map(AdminProgram::id)
			.collect(Collectors.toUnmodifiableSet());
	}

	@ModelAttribute("adminProgramGroups")
	List<AdminProgramGroup> adminProgramGroups(Authentication authentication) {
		return AdminProgram.visibleTo(authentication).stream()
			.collect(Collectors.groupingBy(
				AdminProgram::groupLabel,
				java.util.LinkedHashMap::new,
				Collectors.toList()
			))
			.entrySet()
			.stream()
			.map(entry -> new AdminProgramGroup(entry.getKey(), entry.getValue()))
			.toList();
	}

	@ModelAttribute("adminShell")
	AdminShell adminShell(Authentication authentication) {
		String username = isAuthenticated(authentication) ? authentication.getName() : "anonymous";
		return new AdminShell(
			environmentLabel(),
			environmentTone(),
			username,
			roleLabel(authentication),
			environment.getProperty("easysubway.admin.revision", "local"),
			environment.getProperty("easysubway.admin.master-data-version", "unknown")
		);
	}

	private String environmentLabel() {
		List<String> profiles = activeProfiles();
		if (profiles.contains("staging")) {
			return "STAGING";
		}
		if (profiles.contains("prod")) {
			return "PRODUCTION";
		}
		return profiles.isEmpty() ? "DEV" : profiles.get(0).toUpperCase(java.util.Locale.ROOT);
	}

	private String environmentTone() {
		List<String> profiles = activeProfiles();
		if (profiles.contains("staging")) {
			return "staging";
		}
		if (profiles.contains("prod")) {
			return "production";
		}
		return "development";
	}

	private List<String> activeProfiles() {
		return Arrays.stream(environment.getActiveProfiles()).toList();
	}

	// 사용자 신원 표기는 역할 등급(ROLE_*)만 노출한다. 세부 RBAC 권한(authority) 개수는
	// 내부 구현 상세라 상단바에 드러내지 않는다(#2047 후속 오너 지시).
	private static String roleLabel(Authentication authentication) {
		if (!isAuthenticated(authentication)) {
			return "권한 없음";
		}
		List<String> roles = authentication.getAuthorities().stream()
			.map(GrantedAuthority::getAuthority)
			.filter(authority -> authority.startsWith("ROLE_"))
			.map(authority -> authority.substring("ROLE_".length()))
			.sorted(Comparator.naturalOrder())
			.toList();
		if (roles.isEmpty()) {
			return "권한 없음";
		}
		return String.join(", ", roles);
	}

	private static boolean isAuthenticated(Authentication authentication) {
		return authentication != null
			&& authentication.isAuthenticated()
			&& !(authentication instanceof AnonymousAuthenticationToken);
	}

	record AdminProgramGroup(String label, List<AdminProgram> programs) {
	}

	record AdminShell(
		String environmentLabel,
		String environmentTone,
		String username,
		String rolesLabel,
		String revision,
		String masterDataVersion
	) {
	}
}
