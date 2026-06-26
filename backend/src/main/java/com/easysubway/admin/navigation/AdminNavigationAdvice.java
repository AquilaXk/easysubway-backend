package com.easysubway.admin.navigation;

import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

@ControllerAdvice
class AdminNavigationAdvice {

	@ModelAttribute("adminProgramIds")
	Set<String> adminProgramIds(Authentication authentication) {
		return AdminProgram.visibleTo(authentication).stream()
			.map(AdminProgram::id)
			.collect(Collectors.toUnmodifiableSet());
	}
}
