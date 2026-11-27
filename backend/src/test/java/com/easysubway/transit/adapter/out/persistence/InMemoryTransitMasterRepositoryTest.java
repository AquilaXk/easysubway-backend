package com.easysubway.transit.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.easysubway.transit.domain.AccessibilityFacilityStatus;
import com.easysubway.transit.domain.SimplifiedStationLayout;
import com.easysubway.transit.domain.SimplifiedStationLayoutStatus;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("인메모리 도시철도 마스터 저장소")
class InMemoryTransitMasterRepositoryTest {

	@Test
	@DisplayName("단건 역·시설 조회는 null과 미존재 키를 빈 Optional로 돌려주고 시드 키는 찾는다")
	void singleStationAndFacilityLookupsHandleNullMissingAndPresentIds() {
		var repository = new InMemoryTransitMasterRepository();

		assertThat(repository.loadStation(null)).isEmpty();
		assertThat(repository.loadStation("station-nonexistent")).isEmpty();
		assertThat(repository.loadStation("station-sangnoksu")).isPresent();

		assertThat(repository.loadAccessibilityFacility(null)).isEmpty();
		assertThat(repository.loadAccessibilityFacility("facility-nonexistent")).isEmpty();
		assertThat(repository.loadAccessibilityFacility("facility-sangnoksu-elevator-1")).isPresent();
	}

	@Test
	@DisplayName("알 수 없는 시설·배치 상태 갱신은 무시하고 배치 상태 변경은 PUBLISHED 여부를 구분한다")
	void statusUpdatesIgnoreMissingTargetsAndPreservePublishedAtWhenNotPublished() {
		var repository = new InMemoryTransitMasterRepository();
		LocalDate updatedAt = LocalDate.of(2026, 10, 17);

		repository.saveFacilityStatus("missing-facility", AccessibilityFacilityStatus.BROKEN, updatedAt);
		repository.saveSimplifiedStationLayoutStatus(
			"missing-layout",
			SimplifiedStationLayoutStatus.PUBLISHED,
			"reviewer-1",
			updatedAt
		);

		List<SimplifiedStationLayout> seededLayouts = repository.loadSimplifiedStationLayouts();
		assertThat(seededLayouts).isNotEmpty();
		String layoutId = seededLayouts.getFirst().id();
		LocalDate initialPublishedAt = seededLayouts.getFirst().publishedAt();

		// 비PUBLISHED 상태로 변경 시 publishedAt은 기존 값을 유지한다.
		repository.saveSimplifiedStationLayoutStatus(
			layoutId,
			SimplifiedStationLayoutStatus.ARCHIVED,
			"reviewer-2",
			updatedAt
		);
		var afterArchived = repository.loadSimplifiedStationLayouts().stream()
			.filter(layout -> layout.id().equals(layoutId))
			.findFirst()
			.orElseThrow();
		assertThat(afterArchived.status()).isEqualTo(SimplifiedStationLayoutStatus.ARCHIVED);
		assertThat(afterArchived.publishedAt()).isEqualTo(initialPublishedAt);

		// PUBLISHED 상태로 변경 시 publishedAt이 updatedAt으로 갱신된다.
		repository.saveSimplifiedStationLayoutStatus(
			layoutId,
			SimplifiedStationLayoutStatus.PUBLISHED,
			"reviewer-3",
			updatedAt
		);
		var afterPublished = repository.loadSimplifiedStationLayouts().stream()
			.filter(layout -> layout.id().equals(layoutId))
			.findFirst()
			.orElseThrow();
		assertThat(afterPublished.status()).isEqualTo(SimplifiedStationLayoutStatus.PUBLISHED);
		assertThat(afterPublished.publishedAt()).isEqualTo(updatedAt);
	}
}
