package com.easysubway.common.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.easysubway.collection.adapter.out.persistence.InMemoryDataCollectionRunRepository;
import com.easysubway.favorite.adapter.out.persistence.InMemoryFavoriteFacilityRepository;
import com.easysubway.favorite.adapter.out.persistence.InMemoryFavoriteRouteRepository;
import com.easysubway.favorite.adapter.out.persistence.InMemoryFavoriteStationRepository;
import com.easysubway.notification.adapter.out.persistence.InMemoryNotificationPreferenceRepository;
import com.easysubway.notification.adapter.out.persistence.InMemoryPushNotificationOutboxRepository;
import com.easysubway.profile.adapter.out.persistence.InMemoryMobilityProfileRepository;
import com.easysubway.report.adapter.out.persistence.InMemoryFacilityReportRepository;
import com.easysubway.report.adapter.out.persistence.InMemoryFacilityReportReviewAuditRepository;
import com.easysubway.route.adapter.out.persistence.InMemoryRouteSearchRepository;
import com.easysubway.transit.adapter.out.persistence.InMemoryTransitMasterRepository;
import com.easysubway.usage.adapter.out.persistence.InMemoryUserActivityRepository;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.context.annotation.Profile;

@DisplayName("인메모리 저장소 프로필 설정")
class InMemoryRepositoryProfileTest {

	@ParameterizedTest(name = "{0}")
	@MethodSource("inMemoryRepositories")
	@DisplayName("인메모리 저장소는 운영 프로필에서 빈으로 등록하지 않는다")
	void inMemoryRepositoriesAreDisabledOnProd(Class<?> repositoryType) {
		Profile profile = repositoryType.getAnnotation(Profile.class);

		assertThat(profile)
			.as("%s 클래스는 @Profile(\"!prod\")가 필요합니다.", repositoryType.getSimpleName())
			.isNotNull();
		assertThat(profile.value()).containsExactly("!prod");
	}

	@Test
	@DisplayName("검증 대상에는 운영 데이터가 유실될 수 있는 인메모리 저장소를 모두 포함한다")
	void allStatefulInMemoryRepositoriesAreCovered() {
		assertThat(inMemoryRepositories()).hasSize(12);
	}

	static Stream<Class<?>> inMemoryRepositories() {
		return Stream.of(
			InMemoryDataCollectionRunRepository.class,
			InMemoryFavoriteFacilityRepository.class,
			InMemoryFavoriteRouteRepository.class,
			InMemoryFavoriteStationRepository.class,
			InMemoryFacilityReportRepository.class,
			InMemoryFacilityReportReviewAuditRepository.class,
			InMemoryMobilityProfileRepository.class,
			InMemoryNotificationPreferenceRepository.class,
			InMemoryPushNotificationOutboxRepository.class,
			InMemoryRouteSearchRepository.class,
			InMemoryTransitMasterRepository.class,
			InMemoryUserActivityRepository.class
		);
	}
}
