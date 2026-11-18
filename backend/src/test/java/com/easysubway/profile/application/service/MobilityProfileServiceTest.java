package com.easysubway.profile.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.easysubway.profile.adapter.out.persistence.InMemoryMobilityProfileRepository;
import com.easysubway.profile.application.port.in.SaveMobilityProfileCommand;
import com.easysubway.profile.domain.InvalidMobilityProfileException;
import com.easysubway.profile.domain.MobilityType;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("이동 프로필 서비스")
class MobilityProfileServiceTest {

	private final InMemoryMobilityProfileRepository repository = new InMemoryMobilityProfileRepository();
	private final MobilityProfileService service = new MobilityProfileService(
		repository,
		repository,
		Clock.fixed(Instant.parse("2026-06-12T00:00:00Z"), ZoneId.of("Asia/Seoul"))
	);

	@Test
	@DisplayName("새 익명 사용자는 고령자 기본 이동 프로필을 받는다")
	void getProfileReturnsDefaultProfileForNewAnonymousUser() {
		var profile = service.getProfile("anonymous-user-1");

		assertThat(profile.userId()).isEqualTo("anonymous-user-1");
		assertThat(profile.mobilityType()).isEqualTo(MobilityType.SENIOR);
		assertThat(profile.avoidStairs()).isTrue();
		assertThat(profile.requireElevator()).isFalse();
		assertThat(profile.allowEscalator()).isTrue();
		assertThat(profile.minimizeTransfers()).isTrue();
		assertThat(profile.avoidLongWalks()).isTrue();
		assertThat(profile.largeText()).isFalse();
		assertThat(profile.highContrast()).isFalse();
		assertThat(profile.simpleView()).isFalse();
		assertThat(profile.updatedAt()).isEqualTo(LocalDateTime.of(2026, 6, 12, 9, 0));
	}

	@Test
	@DisplayName("이동 유형과 접근성 선호 설정을 저장한다")
	void saveProfileStoresMobilityAndAccessibilityPreferences() {
		var profile = service.saveProfile(new SaveMobilityProfileCommand(
			"anonymous-user-1",
			MobilityType.STROLLER,
			true,
			true,
			true,
			false,
			true,
			true,
			true,
			false
		));

		assertThat(profile.mobilityType()).isEqualTo(MobilityType.STROLLER);
		assertThat(profile.requireElevator()).isTrue();
		assertThat(profile.largeText()).isTrue();
		assertThat(profile.highContrast()).isTrue();
		assertThat(profile.simpleView()).isFalse();
		assertThat(profile.updatedAt()).isEqualTo(LocalDateTime.of(2026, 6, 12, 9, 0));
		assertThat(service.getProfile("anonymous-user-1")).isEqualTo(profile);
	}

	@Test
	@DisplayName("휠체어 프로필은 계단 허용 설정으로 저장할 수 없다")
	void saveProfileRejectsWheelchairProfileThatAllowsStairs() {
		assertThatThrownBy(() -> service.saveProfile(new SaveMobilityProfileCommand(
			"anonymous-user-1",
			MobilityType.WHEELCHAIR,
			false,
			true,
			false,
			true,
			true,
			false,
			false,
			true
		)))
			.isInstanceOf(InvalidMobilityProfileException.class)
			.hasMessage("휠체어 프로필은 계단 없는 경로만 저장할 수 있습니다.");
	}

	@Test
	@DisplayName("프로필 저장은 사용자 식별자와 이동 유형을 요구한다")
	void saveProfileRequiresUserIdAndMobilityType() {
		assertThatThrownBy(() -> service.saveProfile(new SaveMobilityProfileCommand(
			"",
			MobilityType.SENIOR,
			true,
			false,
			true,
			true,
			true,
			false,
			false,
			false
		)))
			.isInstanceOf(InvalidMobilityProfileException.class)
			.hasMessage("사용자 식별자가 필요합니다.");

		assertThatThrownBy(() -> service.saveProfile(new SaveMobilityProfileCommand(
			"anonymous-user-1",
			null,
			true,
			false,
			true,
			true,
			true,
			false,
			false,
			false
		)))
			.isInstanceOf(InvalidMobilityProfileException.class)
			.hasMessage("이동 유형을 선택해야 합니다.");
	}
}
