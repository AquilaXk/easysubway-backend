package com.easysubway.profile.application.port.out;

import com.easysubway.profile.domain.MobilityProfile;

public interface SaveMobilityProfilePort {

	MobilityProfile saveProfile(MobilityProfile profile);
}
