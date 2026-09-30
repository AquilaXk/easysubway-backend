package com.easysubway.transit.application.service;

import com.easysubway.transit.application.port.out.FacilityOperationalStatusStore;
import com.easysubway.transit.application.port.out.LoadBundleElevatorFacilitiesPort;
import com.easysubway.transit.application.port.out.LoadBundleElevatorFacilitiesPort.BundleElevatorFacility;
import com.easysubway.transit.domain.FacilityOperationalState;
import com.easysubway.transit.domain.SmrtElevatorFacilityIds;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * 관리자가 확인한 시설 운영 상태를 기록하는 경로(#419). 미검증 시민 제보는 여기로 쓰지 않고, 관리자 화면(제보 확인 대기열의
 * 엘리베이터 가동 상태 확인 기록)에서 관리자가 확인한 결과만 {@code ADMIN_VERIFIED}로 남긴다. 대상 시설은 활성 경로 번들의
 * {@code smrt-elev} 목록에서 고른 정규 id만 받는다.
 *
 * <p>우선순위({@link com.easysubway.transit.domain.FacilityOperationalStatusPriority}): 관리자 확인은 원천이 확인 당시와 같은
 * 코드를 되풀이하는 동안 유지되고, 확인 뒤 원천 값이 바뀐 것이 관측되면 원천 값으로 바뀐다. 확인 시각보다 새 관측이 이미 있으면
 * 기록하지 않는다.
 */
@Service
public class FacilityOperationalStatusAdminService {

	private final FacilityOperationalStatusStore store;
	private final LoadBundleElevatorFacilitiesPort bundleFacilities;
	private final Clock clock;

	@Autowired
	public FacilityOperationalStatusAdminService(
		FacilityOperationalStatusStore store,
		LoadBundleElevatorFacilitiesPort bundleFacilities
	) {
		this(store, bundleFacilities, Clock.systemUTC());
	}

	FacilityOperationalStatusAdminService(
		FacilityOperationalStatusStore store,
		LoadBundleElevatorFacilitiesPort bundleFacilities,
		Clock clock
	) {
		this.store = store;
		this.bundleFacilities = bundleFacilities;
		this.clock = clock;
	}

	/**
	 * @throws CatalogUnavailableException 활성 번들 목록을 읽을 수 없을 때
	 */
	public List<BundleElevatorFacility> selectableFacilities() {
		return bundleFacilities.loadActiveBundleElevatorFacilities().orElseThrow(CatalogUnavailableException::new);
	}

	/**
	 * @return 기록했으면 {@code true}, 확인 시각보다 새 관측이 이미 있어 기록하지 않았으면 {@code false}
	 * @throws IllegalArgumentException 정규 smrt-elev id가 아니거나, 활성 번들 목록에 없거나, 상태가 없을 때
	 * @throws CatalogUnavailableException 활성 번들 목록을 읽을 수 없을 때
	 */
	public boolean recordAdminVerified(String facilityId, FacilityOperationalState state) {
		if (!SmrtElevatorFacilityIds.isCanonical(facilityId)) {
			throw new IllegalArgumentException("facilityId must be a canonical smrt-elev identifier");
		}
		if (state == null) {
			throw new IllegalArgumentException("state is required");
		}
		boolean listed = selectableFacilities().stream().anyMatch(facility -> facility.facilityId().equals(facilityId));
		if (!listed) {
			throw new IllegalArgumentException("facilityId is not in the active route bundle smrt-elev facility list");
		}
		return store.recordAdminVerified(facilityId, state, clock.instant().truncatedTo(ChronoUnit.MICROS));
	}

	/** 활성 경로 번들의 smrt-elev 시설 목록을 읽을 수 없다. 대신 쓸 목록은 없다. */
	public static final class CatalogUnavailableException extends IllegalStateException {

		private static final long serialVersionUID = 1L;

		CatalogUnavailableException() {
			super("active route bundle smrt-elev facility list is unavailable");
		}
	}
}
