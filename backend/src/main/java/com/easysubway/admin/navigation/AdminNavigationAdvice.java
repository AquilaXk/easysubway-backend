package com.easysubway.admin.navigation;

import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.core.env.Environment;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
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
			rolesLabel(authentication),
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

	private static String rolesLabel(Authentication authentication) {
		if (!isAuthenticated(authentication)) {
			return "권한 없음";
		}
		List<String> authorities = authentication.getAuthorities().stream()
			.map(authority -> authority.getAuthority().replaceFirst("^ROLE_", ""))
			.sorted(Comparator.naturalOrder())
			.toList();
		if (authorities.isEmpty()) {
			return "권한 없음";
		}
		if (authorities.size() == 1) {
			return authorities.get(0);
		}
		return authorities.get(0) + " 외 " + (authorities.size() - 1) + "개";
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
