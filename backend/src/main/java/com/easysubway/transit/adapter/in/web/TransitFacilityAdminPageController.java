package com.easysubway.transit.adapter.in.web;

import com.easysubway.admin.web.AdminFormErrorView;
import com.easysubway.transit.application.port.in.TransitMasterAdminUseCase;
import com.easysubway.transit.application.port.in.UpdateAccessibilityFacilityStatusCommand;
import com.easysubway.transit.domain.AccessibilityFacilityStatus;
import com.easysubway.transit.domain.MasterDataWriteNotAllowedException;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.security.Principal;
import java.util.Arrays;
import java.util.List;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.validation.BindingResult;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
class TransitFacilityAdminPageController {

	private final TransitFacilityStatusAssembler facilityStatusAssembler;
	private final TransitMasterAdminUseCase transitMasterAdminUseCase;

	TransitFacilityAdminPageController(
		TransitFacilityStatusAssembler facilityStatusAssembler,
		TransitMasterAdminUseCase transitMasterAdminUseCase
	) {
		this.facilityStatusAssembler = facilityStatusAssembler;
		this.transitMasterAdminUseCase = transitMasterAdminUseCase;
	}

	@GetMapping("/admin/facilities/page")
	String facilitiesPage(Model model) {
		model.addAttribute("facilities", facilityStatusAssembler.assemble());
		model.addAttribute("statusOptions", statusOptions());
		model.addAttribute("masterDataWritable", transitMasterAdminUseCase.masterDataCapability().writable());
		return "admin/facilities/list";
	}

	@PostMapping("/admin/facilities/{facilityId}/page/status")
	@PreAuthorize("hasAuthority('admin.master.edit')")
	String updateFacilityStatusFromPage(
		@PathVariable String facilityId,
		@Valid @ModelAttribute("facilityStatusForm") FacilityStatusForm form,
		BindingResult bindingResult,
		Principal principal,
		RedirectAttributes redirectAttributes,
		Model model,
		HttpServletResponse response
	) {
		if (bindingResult.hasErrors()) {
			response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
			facilitiesPage(model);
			AdminFormErrorView.expose(model, bindingResult);
			return "admin/facilities/list";
		}
		try {
			transitMasterAdminUseCase.updateFacilityStatus(new UpdateAccessibilityFacilityStatusCommand(
				facilityId,
				form.status(),
				principal.getName()
			));
		} catch (MasterDataWriteNotAllowedException exception) {
			redirectAttributes.addFlashAttribute("masterDataError", exception.getMessage());
		}
		return "redirect:/admin/facilities/page";
	}

	private static List<FacilityStatusOption> statusOptions() {
		return Arrays.stream(AccessibilityFacilityStatus.values())
			.map(status -> new FacilityStatusOption(status, FacilityStatusRow.statusLabel(status)))
			.toList();
	}

	record FacilityStatusOption(AccessibilityFacilityStatus value, String label) {
	}

	record FacilityStatusForm(
		@NotNull(message = "{validation.transit.facility-status.required}")
		AccessibilityFacilityStatus status
	) {
	}
}
