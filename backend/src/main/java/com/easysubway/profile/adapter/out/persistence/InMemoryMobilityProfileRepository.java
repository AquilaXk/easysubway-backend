package com.easysubway.profile.adapter.out.persistence;

import com.easysubway.profile.application.port.out.LoadMobilityProfilePort;
import com.easysubway.profile.application.port.out.SaveMobilityProfilePort;
import com.easysubway.profile.domain.MobilityProfile;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.springframework.stereotype.Repository;

@Repository
public class InMemoryMobilityProfileRepository implements LoadMobilityProfilePort, SaveMobilityProfilePort {

	private final ConcurrentMap<String, MobilityProfile> profiles = new ConcurrentHashMap<>();

	@Override
	public Optional<MobilityProfile> loadProfile(String userId) {
		return Optional.ofNullable(profiles.get(userId));
	}

	@Override
	public MobilityProfile saveProfile(MobilityProfile profile) {
		profiles.put(profile.userId(), profile);
		return profile;
	}
}
