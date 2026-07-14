package com.easysubway.train.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("기차검색 열차종 범위 계약")
class TrainSearchScopePolicyTest {

	@Test
	@DisplayName("station filter와 search query는 ITX-청춘을 같은 error code로 거부한다")
	void stationAndSearchRequestsRejectItxCheongchun() {
		for (String requestSurface : List.of("stations", "search")) {
			assertThatThrownBy(() -> TrainSearchScopePolicy.requireSupported("ITX_CHEONGCHUN"))
				.as(requestSurface)
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessage("TRAIN_SEARCH_UNSUPPORTED_TRAIN_TYPE: 지원하지 않는 열차종입니다.");
		}
	}

	@Test
	@DisplayName("provider current·legacy ITX row는 cache·catalog·response 정규화에서 제거한다")
	void normalizationRetainsOnlySupportedRows() {
		List<ProviderRow> rows = List.of(
			new ProviderRow("청량리", "춘천", "ITX_CHEONGCHUN"),
			new ProviderRow("용산", "대전", "ITX_CHEONGCHUN"),
			new ProviderRow("서울", "대전", "KTX")
		);

		assertThat(TrainSearchScopePolicy.retainSupported(rows, ProviderRow::trainType))
			.containsExactly(new ProviderRow("서울", "대전", "KTX"));
	}

	@Test
	@DisplayName("지원 열차종 allowlist는 대전 KTX를 유지하고 ITX-청춘을 포함하지 않는다")
	void supportedTypesKeepDaejeonKtxWithoutItxCheongchun() {
		assertThat(TrainSearchScopePolicy.supportedTrainTypes())
			.contains("KTX")
			.doesNotContain("ITX_CHEONGCHUN");
		assertThat(TrainSearchScopePolicy.requireSupported(" KTX ")).isEqualTo("KTX");
	}

	private record ProviderRow(String departureStation, String arrivalStation, String trainType) {}
}
