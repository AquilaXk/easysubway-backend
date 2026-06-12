package com.easysubway.profile.application.service;

import com.easysubway.profile.application.port.in.MobilityProfileUseCase;
import com.easysubway.profile.application.port.in.SaveMobilityProfileCommand;
import com.easysubway.profile.application.port.out.LoadMobilityProfilePort;
import com.easysubway.profile.application.port.out.SaveMobilityProfilePort;
import com.easysubway.profile.domain.InvalidMobilityProfileException;
import com.easysubway.profile.domain.MobilityProfile;
import com.easysubway.profile.domain.MobilityType;
import java.time.Clock;
import java.time.LocalDateTime;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class MobilityProfileService implements MobilityProfileUseCase {

	private final LoadMobilityProfilePort loadMobilityProfilePort;
	private final SaveMobilityProfilePort saveMobilityProfilePort;
	private final Clock clock;

	@Autowired
	public MobilityProfileService(
		LoadMobilityProfilePort loadMobilityProfilePort,
		SaveMobilityProfilePort saveMobilityProfilePort
	) {
		this(loadMobilityProfilePort, saveMobilityProfilePort, Clock.systemDefaultZone());
	}

	public MobilityProfileService(
		LoadMobilityProfilePort loadMobilityProfilePort,
		SaveMobilityProfilePort saveMobilityProfilePort,
		Clock clock
	) {
		this.loadMobilityProfilePort = loadMobilityProfilePort;
		this.saveMobilityProfilePort = saveMobilityProfilePort;
		this.clock = clock;
	}

	@Override
	public MobilityProfile getProfile(String userId) {
		requireUserId(userId);
		return loadMobilityProfilePort.loadProfile(userId)
			.orElseGet(() -> defaultProfile(userId));
	}

	@Override
	public MobilityProfile saveProfile(SaveMobilityProfileCommand command) {
		requireUserId(command.userId());
		requireMobilityType(command.mobilityType());
		requireWheelchairRules(command);

		return saveMobilityProfilePort.saveProfile(new MobilityProfile(
			command.userId(),
			command.mobilityType(),
			command.avoidStairs(),
			command.requireElevator(),
			command.allowEscalator(),
			command.minimizeTransfers(),
			command.avoidLongWalks(),
			command.largeText(),
			command.highContrast(),
			command.simpleView(),
			LocalDateTime.now(clock)
		));
	}

	private MobilityProfile defaultProfile(String userId) {
		return new MobilityProfile(
			userId,
			MobilityType.SENIOR,
			true,
			false,
			true,
			true,
			true,
			false,
			false,
			false,
			LocalDateTime.now(clock)
		);
	}

	private void requireUserId(String userId) {
		if (userId == null || userId.isBlank()) {
			throw new InvalidMobilityProfileException("사용자 식별자가 필요합니다.");
		}
	}

	private void requireMobilityType(MobilityType mobilityType) {
		if (mobilityType == null) {
			throw new InvalidMobilityProfileException("이동 유형을 선택해야 합니다.");
		}
	}

	private void requireWheelchairRules(SaveMobilityProfileCommand command) {
		if (command.mobilityType() == MobilityType.WHEELCHAIR && !command.avoidStairs()) {
			throw new InvalidMobilityProfileException("휠체어 프로필은 계단 없는 경로만 저장할 수 있습니다.");
		}
	}
}
