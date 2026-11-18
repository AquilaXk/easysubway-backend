package com.easysubway.admin.alert;

import io.github.wimdeblauwe.htmx.spring.boot.mvc.HxRequest;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * 관리자 알림 센터(#1738). 일반 요청은 알림 전용 페이지(no-JS 동작), {@code HX-Request}는
 * topbar 벨이 60초 폴링하는 요약 패널 fragment만 반환한다.
 *
 * <p>알림 집약 질의는 이 엔드포인트에서만 실행된다. 각 화면의 본문 컨트롤러는 알림을 계산하지
 * 않으므로 일반 페이지 로드의 query budget에 영향을 주지 않는다(폴링 질의만 격리).
 */
@Controller
class AdminAlertController {

	private final AdminAlertService alertService;

	AdminAlertController(AdminAlertService alertService) {
		this.alertService = alertService;
	}

	@GetMapping("/admin/alerts")
	String alertsPage(Authentication authentication, Model model) {
		populate(authentication, model);
		return "admin/alerts";
	}

	@HxRequest
	@GetMapping("/admin/alerts")
	String alertsPanel(Authentication authentication, Model model) {
		populate(authentication, model);
		return "admin/alerts :: panel";
	}

	private void populate(Authentication authentication, Model model) {
		model.addAttribute("alertSummary", alertService.summarize(authentication));
	}
}
