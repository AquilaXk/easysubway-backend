package com.easysubway.profile.application.port.out;

import com.easysubway.profile.domain.MobilityProfile;
import java.util.Optional;

public interface LoadMobilityProfilePort {

	Optional<MobilityProfile> loadProfile(String userId);
}
