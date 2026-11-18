package com.easysubway.admin.search;

import io.github.wimdeblauwe.htmx.spring.boot.mvc.HxRequest;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * 관리자 통합 검색(#1738). 일반 요청은 검색 전용 페이지(no-JS 동작), {@code HX-Request}는
 * 결과 fragment만 반환한다(커맨드 팔레트가 300ms debounce로 호출).
 */
@Controller
class AdminSearchController {

	private final AdminSearchService searchService;

	AdminSearchController(AdminSearchService searchService) {
		this.searchService = searchService;
	}

	@GetMapping("/admin/search")
	String searchPage(
		@RequestParam(name = "q", required = false) String query,
		Authentication authentication,
		Model model
	) {
		populate(query, authentication, model);
		return "admin/search";
	}

	@HxRequest
	@GetMapping("/admin/search")
	String searchResults(
		@RequestParam(name = "q", required = false) String query,
		Authentication authentication,
		Model model
	) {
		populate(query, authentication, model);
		return "admin/search :: results";
	}

	private void populate(String query, Authentication authentication, Model model) {
		model.addAttribute("query", query);
		model.addAttribute("resultGroups", searchService.search(query, authentication));
	}
}
