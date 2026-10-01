package com.easysubway.transit.adapter.in.web;

import com.easysubway.admin.audit.application.service.AdminAuditWriter;
import com.easysubway.admin.audit.domain.AdminAuditOutcome;
import com.easysubway.transit.application.port.out.FacilityOperationalStatusStore.AdminVerifiedResult;
import com.easysubway.transit.application.port.out.LoadBundleElevatorFacilitiesPort.BundleElevatorFacility;
import com.easysubway.transit.application.service.FacilityOperationalStatusAdminService;
import com.easysubway.transit.application.service.FacilityOperationalStatusAdminService.CatalogUnavailableException;
import com.easysubway.transit.domain.FacilityOperationalState;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * 제보 확인 대기열의 엘리베이터 가동 상태 확인 기록 화면(#419). 제보 검수 권한({@code admin.report.review})과 기존 관리자
 * CSRF·command token 흐름을 그대로 쓰고, 관리자가 활성 경로 번들의 {@code smrt-elev} 목록에서 고른 시설의 확인 상태만
 * {@code ADMIN_VERIFIED}로 기록한다. 시민 제보 접수는 이 기록을 만들지 않는다.
 */
@Controller
class FacilityOperationalStatusAdminPageController {

	static final String PAGE = "/admin/reports/elevator-status/page";
	private static final String VIEW = "admin/reports/elevator-status";
	private static final String AUDIT_TARGET = "FACILITY_OPERATIONAL_STATUS";
	private static final String AUDIT_ACTION = "RECORD_ADMIN_VERIFIED";
	private static final String INVALID_INPUT_MESSAGE = "번들 엘리베이터 목록에서 시설을 골라 주세요. 가동 상태도 함께 골라야 합니다.";
	private static final String UNAVAILABLE_MESSAGE = "활성 경로 번들의 엘리베이터 목록을 불러올 수 없습니다. 번들 적재 상태를 확인한 뒤 다시 시도해 주세요.";
	private static final String CONFLICT_MESSAGE = "확인 시각보다 더 최근 관측이 이미 있어 기록하지 않았습니다. 현재 상태를 다시 확인해 주세요.";
	private static final Logger log = LoggerFactory.getLogger(FacilityOperationalStatusAdminPageController.class);

	private final FacilityOperationalStatusAdminService adminService;
	private final AdminAuditWriter auditWriter;

	FacilityOperationalStatusAdminPageController(
		FacilityOperationalStatusAdminService adminService,
		AdminAuditWriter auditWriter
	) {
		this.adminService = adminService;
		this.auditWriter = auditWriter;
	}

	@GetMapping(PAGE)
	@PreAuthorize("hasAuthority('admin.report.review')")
	String elevatorStatusPage(Model model, HttpServletResponse response) {
		render(model, response, new VerificationForm(null, null), HttpServletResponse.SC_OK, null);
		return VIEW;
	}

	@PostMapping(PAGE + "/verify")
	@PreAuthorize("hasAuthority('admin.report.review')")
	@Transactional
	String recordVerifiedState(
		@ModelAttribute("verificationForm") VerificationForm form,
		BindingResult bindingResult,
		Model model,
		HttpServletResponse response,
		Authentication authentication,
		HttpServletRequest request,
		RedirectAttributes redirectAttributes
	) {
		AdminVerifiedResult result;
		try {
			if (bindingResult.hasErrors()) {
				throw new IllegalArgumentException("verification form is invalid");
			}
			result = adminService.recordAdminVerified(form.facilityId(), form.state());
		} catch (CatalogUnavailableException exception) {
			audit(authentication, request, null, AdminAuditOutcome.FAILURE, "CATALOG_UNAVAILABLE");
			render(model, response, form, HttpServletResponse.SC_SERVICE_UNAVAILABLE, UNAVAILABLE_MESSAGE);
			return VIEW;
		} catch (IllegalArgumentException exception) {
			audit(authentication, request, null, AdminAuditOutcome.FAILURE, "INVALID_INPUT");
			render(model, response, form, HttpServletResponse.SC_BAD_REQUEST, INVALID_INPUT_MESSAGE);
			return VIEW;
		}
		if (!result.recorded()) {
			audit(authentication, request, form.facilityId(), AdminAuditOutcome.FAILURE, "NEWER_OBSERVATION");
			render(model, response, form, HttpServletResponse.SC_CONFLICT, CONFLICT_MESSAGE);
			return VIEW;
		}
		String reason = result.previousState().isPresent()
			? "from=" + result.previousState().get().name() + "/" + result.previousSource().get().name() + " to=" + form.state().name()
			: "from=NONE to=" + form.state().name();
		audit(authentication, request, form.facilityId(), AdminAuditOutcome.SUCCESS, reason);
		log.info("Admin verified facility operational status recorded: facilityId={}, state={}, actor={}, reason={}",
			form.facilityId(), form.state(), authentication.getName(), reason);
		redirectAttributes.addFlashAttribute("flashMessage", "확인한 가동 상태를 기록했습니다.");
		redirectAttributes.addFlashAttribute("flashTone", "good");
		return "redirect:" + PAGE;
	}

	private void render(Model model, HttpServletResponse response, VerificationForm form, int status, String errorMessage) {
		List<FacilityOption> options = List.of();
		boolean catalogAvailable = true;
		int resolvedStatus = status;
		String resolvedError = errorMessage;
		try {
			options = adminService.selectableFacilities().stream().map(FacilityOption::from).toList();
		} catch (CatalogUnavailableException exception) {
			catalogAvailable = false;
			resolvedStatus = HttpServletResponse.SC_SERVICE_UNAVAILABLE;
			resolvedError = UNAVAILABLE_MESSAGE;
		}
		response.setStatus(resolvedStatus);
		model.addAttribute("facilityOptions", options);
		model.addAttribute("catalogAvailable", catalogAvailable);
		model.addAttribute("stateOptions", StateOption.ALL);
		model.addAttribute("verificationForm", form);
		model.addAttribute("errorMessage", resolvedError);
	}

	private void audit(
		Authentication authentication,
		HttpServletRequest request,
		String facilityId,
		AdminAuditOutcome outcome,
		String reason
	) {
		auditWriter.adminAction(authentication, request, AUDIT_TARGET, facilityId, AUDIT_ACTION, outcome, reason);
	}

	record VerificationForm(String facilityId, FacilityOperationalState state) {
	}

	record FacilityOption(String value, String label) {

		static FacilityOption from(BundleElevatorFacility facility) {
			return new FacilityOption(facility.facilityId(), facility.name() + " (" + facility.facilityId() + ")");
		}
	}

	record StateOption(FacilityOperationalState value, String label) {

		static final List<StateOption> ALL = List.of(
			new StateOption(FacilityOperationalState.OPERATING, "가동"),
			new StateOption(FacilityOperationalState.OUT_OF_SERVICE, "가동 불가")
		);
	}
}
