package com.easysubway.transit.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.easysubway.transit.application.port.out.FacilityOperationalStatusStore;
import com.easysubway.transit.application.port.out.FacilityOperationalStatusStore.FeedApplyResult;
import com.easysubway.transit.application.port.out.FacilityOperationalStatusStore.FeedObservation;
import com.easysubway.transit.application.port.out.LoadBundleElevatorFacilitiesPort.BundleElevatorFacility;
import com.easysubway.transit.domain.FacilityOperationalState;
import com.easysubway.transit.domain.FacilityOperationalStatus;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("관리자 확인 시설 운영 상태 기록")
class FacilityOperationalStatusAdminServiceTest {

	private static final Instant NOW = Instant.parse("2026-09-30T01:00:30.123456789Z");

	private static final String EXIT_1 = "smrt-elev:0201:2:1번 출입구";
	private static final String NOT_IN_BUNDLE = "smrt-elev:0201:2:7번 출입구";

	private final RecordingStore store = new RecordingStore();
	private Optional<List<BundleElevatorFacility>> catalog =
		Optional.of(List.of(new BundleElevatorFacility(EXIT_1, "가역 엘리베이터 1번 출입구")));
	private final FacilityOperationalStatusAdminService service =
		new FacilityOperationalStatusAdminService(store, () -> catalog, Clock.fixed(NOW, ZoneOffset.UTC));

	@Test
	@DisplayName("정규 smrt-elev 시설 id의 관리자 확인을 현재 시각(마이크로초)으로 기록한다")
	void recordsAdminVerifiedStatusAtCurrentInstant() {
		store.result = true;

		assertThat(service.recordAdminVerified("smrt-elev:0201:2:1번 출입구", FacilityOperationalState.OUT_OF_SERVICE)).isTrue();

		assertThat(store.records).containsExactly(new AdminRecord(
			"smrt-elev:0201:2:1번 출입구", FacilityOperationalState.OUT_OF_SERVICE, NOW.truncatedTo(ChronoUnit.MICROS)
		));
	}

	@Test
	@DisplayName("더 새 관측이 이미 있으면 기록되지 않았음을 돌려준다")
	void reportsWhenNewerObservationAlreadyExists() {
		store.result = false;

		assertThat(service.recordAdminVerified("smrt-elev:0201:2:1번 출입구", FacilityOperationalState.OPERATING)).isFalse();
	}

	@Test
	@DisplayName("관리자가 고를 수 있는 시설은 활성 번들 smrt-elev 목록이다")
	void selectableFacilitiesComeFromTheActiveBundle() {
		assertThat(service.selectableFacilities())
			.containsExactly(new BundleElevatorFacility(EXIT_1, "가역 엘리베이터 1번 출입구"));
	}

	@Test
	@DisplayName("정규 id라도 활성 번들 목록에 없는 시설은 기록하지 않는다")
	void rejectsCanonicalIdentifierMissingFromTheBundleList() {
		assertThatThrownBy(() -> service.recordAdminVerified(NOT_IN_BUNDLE, FacilityOperationalState.OUT_OF_SERVICE))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("active route bundle");
		assertThat(store.records).isEmpty();
	}

	@Test
	@DisplayName("활성 번들 목록을 읽을 수 없으면 목록 조회와 기록 모두 명시적으로 실패한다")
	void failsExplicitlyWhenTheBundleListIsUnavailable() {
		catalog = Optional.empty();

		assertThatThrownBy(service::selectableFacilities)
			.isInstanceOf(FacilityOperationalStatusAdminService.CatalogUnavailableException.class);
		assertThatThrownBy(() -> service.recordAdminVerified(EXIT_1, FacilityOperationalState.OUT_OF_SERVICE))
			.isInstanceOf(FacilityOperationalStatusAdminService.CatalogUnavailableException.class);
		assertThat(store.records).isEmpty();
	}

	@Test
	@DisplayName("정규 id가 아니거나 상태가 없으면 기록하지 않는다")
	void rejectsNonCanonicalIdentifierOrMissingState() {
		assertThatThrownBy(() -> service.recordAdminVerified("smrt-elev:0201:2:대합실", FacilityOperationalState.OPERATING))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> service.recordAdminVerified("facility-1", FacilityOperationalState.OPERATING))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> service.recordAdminVerified("smrt-elev:0201:2:1번 출입구", null))
			.isInstanceOf(IllegalArgumentException.class);
		assertThat(store.records).isEmpty();
	}

	private record AdminRecord(String facilityId, FacilityOperationalState state, Instant verifiedAt) {
	}

	private static final class RecordingStore implements FacilityOperationalStatusStore {

		private final List<AdminRecord> records = new ArrayList<>();
		private boolean result;

		@Override
		public List<FacilityOperationalStatus> loadStatuses() {
			return List.of();
		}

		@Override
		public Optional<Instant> lastSuccessfulCollectionAt(String feed) {
			return Optional.empty();
		}

		@Override
		public FeedApplyResult applyFeedCollection(String feed, List<FeedObservation> observations, Instant observedAt) {
			throw new UnsupportedOperationException();
		}

		@Override
		public boolean recordAdminVerified(String facilityId, FacilityOperationalState state, Instant verifiedAt) {
			records.add(new AdminRecord(facilityId, state, verifiedAt));
			return result;
		}
	}
}
