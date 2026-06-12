package com.easysubway.profile.application.port.in;

import com.easysubway.profile.domain.MobilityProfile;

public interface MobilityProfileUseCase {

	MobilityProfile getProfile(String userId);

	MobilityProfile saveProfile(SaveMobilityProfileCommand command);
}
