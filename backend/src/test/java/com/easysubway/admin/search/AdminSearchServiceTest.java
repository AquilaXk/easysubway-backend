package com.easysubway.admin.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.easysubway.admin.authorization.AdminPermission;
import com.easysubway.transit.application.port.in.TransitMasterQueryUseCase;
import com.easysubway.transit.domain.DataQualityLevel;
import com.easysubway.transit.domain.DataSourceType;
import com.easysubway.transit.domain.Station;
import com.easysubway.transit.domain.StationWithLines;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

@DisplayName("관리자 통합 검색 서비스")
class AdminSearchServiceTest {

	@Test
	@DisplayName("역 검색은 이름(코드)와 역 상세 링크를 준다")
	void stationSearchReturnsNameCodeAndDetailLink() {
		TransitMasterQueryUseCase transitQuery = mock(TransitMasterQueryUseCase.class);
		when(transitQuery.searchStations(any()))
			.thenReturn(List.of(new StationWithLines(station("station-sangnoksu", "상록수"), List.of())));
		AdminSearchService service = new AdminSearchService(transitQuery);

		List<AdminSearchGroup> groups = service.search("상록수", authWith(AdminPermission.ADMIN_VIEW));

		assertThat(groups)
			.filteredOn(group -> group.type().equals("station"))
			.singleElement()
			.satisfies(group -> assertThat(group.hits()).singleElement().satisfies(hit -> {
				assertThat(hit.label()).isEqualTo("상록수(station-sangnoksu)");
				assertThat(hit.href()).isEqualTo("/admin/stations/station-sangnoksu/page");
			}));
	}

	@Test
	@DisplayName("역 화면 권한이 없으면 역 결과가 제외된다")
	void stationExcludedWithoutStationPermission() {
		TransitMasterQueryUseCase transitQuery = mock(TransitMasterQueryUseCase.class);
		when(transitQuery.searchStations(any()))
			.thenReturn(List.of(new StationWithLines(station("station-sangnoksu", "상록수"), List.of())));
		AdminSearchService service = new AdminSearchService(transitQuery);

		// ADMIN_VIEW가 없는 계정: STATIONS 화면이 안 보이므로 역 결과 제외.
		List<AdminSearchGroup> groups = service.search("상록수", authWith(AdminPermission.AUDIT_READ));

		assertThat(groups).noneSatisfy(group -> assertThat(group.type()).isEqualTo("station"));
	}

	@Test
	@DisplayName("빈 질의는 결과가 없다")
	void blankQueryReturnsNothing() {
		AdminSearchService service = new AdminSearchService(mock(TransitMasterQueryUseCase.class));
		assertThat(service.search("   ", authWith(AdminPermission.ADMIN_VIEW))).isEmpty();
	}

	private static Authentication authWith(AdminPermission permission) {
		return new UsernamePasswordAuthenticationToken(
			"tester", "n/a", List.of(new SimpleGrantedAuthority(permission.authority())));
	}

	private static Station station(String id, String nameKo) {
		return new Station(
			id,
			nameKo,
			"EN",
			"수도권",
			BigDecimal.valueOf(37.3),
			BigDecimal.valueOf(126.8),
			DataQualityLevel.LEVEL_1,
			DataSourceType.ADMIN_VERIFIED,
			LocalDate.of(2026, 1, 1),
			true);
	}
}
