package com.easysubway.profile.adapter.in.web;

import com.easysubway.common.web.ApiResponse;
import com.easysubway.profile.application.port.in.MobilityProfileUseCase;
import com.easysubway.profile.application.port.in.SaveMobilityProfileCommand;
import com.easysubway.profile.domain.MobilityProfile;
import com.easysubway.profile.domain.MobilityType;
import java.time.LocalDateTime;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
class MobilityProfileController {

	private final MobilityProfileUseCase mobilityProfileUseCase;

	MobilityProfileController(MobilityProfileUseCase mobilityProfileUseCase) {
		this.mobilityProfileUseCase = mobilityProfileUseCase;
	}

	@GetMapping("/api/v1/me/mobility-profile")
	ApiResponse<MobilityProfileResponse> getProfile(
		@RequestParam(required = false) String userId
	) {
		return ApiResponse.ok(MobilityProfileResponse.from(mobilityProfileUseCase.getProfile(userId)));
	}

	@PutMapping("/api/v1/me/mobility-profile")
	ApiResponse<MobilityProfileResponse> saveProfile(
		@RequestBody SaveMobilityProfileRequest request
	) {
		MobilityProfile profile = mobilityProfileUseCase.saveProfile(request.toCommand());
		return ApiResponse.ok(MobilityProfileResponse.from(profile));
	}

	record SaveMobilityProfileRequest(
		String userId,
		MobilityType mobilityType,
		boolean avoidStairs,
		boolean requireElevator,
		boolean allowEscalator,
		boolean minimizeTransfers,
		boolean avoidLongWalks,
		boolean largeText,
		boolean highContrast,
		boolean simpleView
	) {

		SaveMobilityProfileCommand toCommand() {
			return new SaveMobilityProfileCommand(
				userId,
				mobilityType,
				avoidStairs,
				requireElevator,
				allowEscalator,
				minimizeTransfers,
				avoidLongWalks,
				largeText,
				highContrast,
				simpleView
			);
		}
	}

	record MobilityProfileResponse(
		String userId,
		MobilityType mobilityType,
		boolean avoidStairs,
		boolean requireElevator,
		boolean allowEscalator,
		boolean minimizeTransfers,
		boolean avoidLongWalks,
		boolean largeText,
		boolean highContrast,
		boolean simpleView,
		LocalDateTime updatedAt
	) {

		static MobilityProfileResponse from(MobilityProfile profile) {
			return new MobilityProfileResponse(
				profile.userId(),
				profile.mobilityType(),
				profile.avoidStairs(),
				profile.requireElevator(),
				profile.allowEscalator(),
				profile.minimizeTransfers(),
				profile.avoidLongWalks(),
				profile.largeText(),
				profile.highContrast(),
				profile.simpleView(),
				profile.updatedAt()
			);
		}
	}
}
