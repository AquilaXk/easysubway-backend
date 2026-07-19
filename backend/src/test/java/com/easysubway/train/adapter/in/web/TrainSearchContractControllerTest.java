package com.easysubway.train.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.easysubway.train.application.TrainSearchService;
import com.easysubway.train.application.TrainSearchService.TrainSearchFailure;
import com.easysubway.train.application.TrainSearchService.StationSearchSnapshot;
import com.easysubway.train.application.TrainSearchService.TrainSearchSnapshot;
import com.easysubway.train.domain.TrainSearchModels.Journey;
import com.easysubway.train.domain.TrainSearchModels.SearchCriteria;
import com.easysubway.train.domain.TrainSearchModels.SearchResult;
import com.easysubway.train.domain.TrainSearchModels.Station;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest(properties = {
	"spring.profiles.active=test",
	"spring.flyway.enabled=false",
	"spring.datasource.url=jdbc:h2:mem:train-search-contract;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
	"spring.datasource.driver-class-name=org.h2.Driver"
})
@AutoConfigureMockMvc
@DisplayName("전국 기차검색 HTTP 계약")
class TrainSearchContractControllerTest {

	@Autowired
	private MockMvc mockMvc;

	@MockitoBean
	private TrainSearchService service;

	@MockitoBean
	private Clock clock;

	@BeforeEach
	void setUpClock() {
		when(clock.instant()).thenReturn(Instant.parse("2026-07-19T00:00:00Z"));
		when(clock.getZone()).thenReturn(ZoneOffset.UTC);
	}

	@Test
	void directItxRequestsReturnTheSameNoStoreErrorEnvelope() throws Exception {
		for (String path : new String[] { "/api/v1/trains/stations", "/api/v1/trains/search" }) {
			mockMvc.perform(get(path).param("trainType", "ITX_CHEONGCHUN"))
				.andExpect(status().isBadRequest())
				.andExpect(header().string("Cache-Control", "no-store"))
				.andExpect(jsonPath("$.success").value(false))
				.andExpect(jsonPath("$.data.code").value("TRAIN_SEARCH_UNSUPPORTED_TRAIN_TYPE"))
				.andExpect(jsonPath("$.message").value("지원하지 않는 열차종입니다."));
		}
	}

	@Test
	void stationSuccessUsesPublicCacheAndExactEtagRevalidation() throws Exception {
		when(service.stationsWithMetadata("서울", "KTX")).thenReturn(new StationSearchSnapshot(
			List.of(new Station("NAT010000", "서울")),
			Instant.parse("2026-07-20T00:00:00Z")
		));

		MvcResult first = mockMvc.perform(get("/api/v1/trains/stations")
				.param("query", "서울")
				.param("trainType", "KTX"))
			.andExpect(status().isOk())
			.andExpect(header().string("Cache-Control", "max-age=300, must-revalidate, public, s-maxage=86400"))
			.andExpect(header().exists("ETag"))
			.andExpect(jsonPath("$.success").value(true))
			.andExpect(jsonPath("$.data[0].id").value("NAT010000"))
			.andReturn();

		mockMvc.perform(get("/api/v1/trains/stations")
				.param("query", "서울")
				.param("trainType", "KTX")
				.header("If-None-Match", first.getResponse().getHeader("ETag")))
			.andExpect(status().isNotModified())
			.andExpect(header().string("ETag", first.getResponse().getHeader("ETag")))
			.andExpect(header().string("Cache-Control", "max-age=300, must-revalidate, public, s-maxage=86400"));
	}

	@Test
	void publicSecurityChainAllowsHeadWithoutAResponseBody() throws Exception {
		when(service.stationsWithMetadata("서울", "KTX")).thenReturn(new StationSearchSnapshot(
			List.of(new Station("NAT010000", "서울")),
			Instant.parse("2026-07-20T00:00:00Z")
		));

		mockMvc.perform(head("/api/v1/trains/stations")
				.param("query", "서울")
				.param("trainType", "KTX"))
			.andExpect(status().isOk())
			.andExpect(header().exists("ETag"))
			.andExpect(result -> assertThat(result.getResponse().getContentAsByteArray()).isEmpty());
	}

	@Test
	void stationRevalidationAcceptsWeakListedAndWildcardIfNoneMatchValidators() throws Exception {
		when(service.stationsWithMetadata("서울", "KTX")).thenReturn(new StationSearchSnapshot(
			List.of(new Station("NAT010000", "서울")),
			Instant.parse("2026-07-20T00:00:00Z")
		));

		MvcResult first = mockMvc.perform(get("/api/v1/trains/stations")
				.param("query", "서울")
				.param("trainType", "KTX"))
			.andExpect(status().isOk())
			.andReturn();
		String etag = first.getResponse().getHeader("ETag");

		for (String validator : List.of("W/" + etag, "\"other\", " + etag, "*")) {
			mockMvc.perform(get("/api/v1/trains/stations")
					.param("query", "서울")
					.param("trainType", "KTX")
					.header("If-None-Match", validator))
				.andExpect(status().isNotModified())
				.andExpect(header().string("ETag", etag));
		}
	}

	@Test
	void todaySearchUsesShortPublicCacheAndReturnsApprovedFields() throws Exception {
		when(service.searchWithMetadata(any())).thenReturn(new TrainSearchSnapshot(
			result(),
			Instant.parse("2026-07-19T00:05:00Z")
		));

		mockMvc.perform(get("/api/v1/trains/search")
				.param("departureStationId", "NAT010000")
				.param("arrivalStationId", "NAT011668")
				.param("departureDate", "2026-07-19")
				.param("returnDate", "2026-07-20")
				.param("trainType", "KTX"))
			.andExpect(status().isOk())
			.andExpect(header().string("Cache-Control", "max-age=60, must-revalidate, public, s-maxage=300"))
			.andExpect(jsonPath("$.data.outbound[0].trainNumber").value("101"))
			.andExpect(jsonPath("$.data.outbound[0].adultFareWon").value(23700))
			.andExpect(jsonPath("$.data.inbound").isEmpty());

		ArgumentCaptor<SearchCriteria> criteria = ArgumentCaptor.forClass(SearchCriteria.class);
		verify(service).searchWithMetadata(criteria.capture());
		assertThat(criteria.getValue()).isEqualTo(new SearchCriteria(
			"NAT010000",
			"NAT011668",
			LocalDate.parse("2026-07-19"),
			LocalDate.parse("2026-07-20"),
			"KTX"
		));
	}

	@Test
	void futureSearchCapsPublicCacheAtTheRemainingSourceTtl() throws Exception {
		when(service.searchWithMetadata(any())).thenReturn(new TrainSearchSnapshot(
			result(),
			Instant.parse("2026-07-19T00:00:45Z")
		));

		mockMvc.perform(get("/api/v1/trains/search")
				.param("departureStationId", "NAT010000")
				.param("arrivalStationId", "NAT011668")
				.param("departureDate", "2026-07-20")
				.param("trainType", "KTX"))
			.andExpect(status().isOk())
			.andExpect(header().string("Cache-Control", "max-age=45, must-revalidate, public, s-maxage=45"));
	}

	@Test
	void overnightSearchUsesTodayCacheUntilTheThreeAmServiceDayBoundary() throws Exception {
		when(clock.instant()).thenReturn(Instant.parse("2026-07-19T17:30:00Z"));
		when(service.searchWithMetadata(any())).thenReturn(new TrainSearchSnapshot(
			result(),
			Instant.parse("2026-07-19T17:35:00Z")
		));

		mockMvc.perform(get("/api/v1/trains/search")
				.param("departureStationId", "NAT010000")
				.param("arrivalStationId", "NAT011668")
				.param("departureDate", "2026-07-19")
				.param("trainType", "KTX"))
			.andExpect(status().isOk())
			.andExpect(header().string("Cache-Control", "max-age=60, must-revalidate, public, s-maxage=300"));
	}

	@Test
	void selectsCachePolicyAfterASearchCrossesTheThreeAmServiceDayBoundary() throws Exception {
		when(clock.instant()).thenReturn(Instant.parse("2026-07-19T17:59:00Z"));
		when(service.searchWithMetadata(any())).thenAnswer(ignored -> {
			when(clock.instant()).thenReturn(Instant.parse("2026-07-19T18:00:00Z"));
			return new TrainSearchSnapshot(result(), Instant.parse("2026-07-19T18:05:00Z"));
		});

		mockMvc.perform(get("/api/v1/trains/search")
				.param("departureStationId", "NAT010000")
				.param("arrivalStationId", "NAT011668")
				.param("departureDate", "2026-07-20")
				.param("trainType", "KTX"))
			.andExpect(status().isOk())
			.andExpect(header().string("Cache-Control", "max-age=60, must-revalidate, public, s-maxage=300"));
	}

	@Test
	void doesNotCacheAServiceDayThatBecomesPastWhileSearching() throws Exception {
		when(clock.instant()).thenReturn(Instant.parse("2026-07-19T17:59:00Z"));
		when(service.searchWithMetadata(any())).thenAnswer(ignored -> {
			when(clock.instant()).thenReturn(Instant.parse("2026-07-19T18:00:00Z"));
			return new TrainSearchSnapshot(result(), Instant.parse("2026-07-19T18:05:00Z"));
		});

		mockMvc.perform(get("/api/v1/trains/search")
				.param("departureStationId", "NAT010000")
				.param("arrivalStationId", "NAT011668")
				.param("departureDate", "2026-07-19")
				.param("trainType", "KTX"))
			.andExpect(status().isOk())
			.andExpect(header().string("Cache-Control", "no-store"));
	}

	@Test
	void malformedDateAndServiceFailuresUseStableNoStoreCodes() throws Exception {
		mockMvc.perform(get("/api/v1/trains/search"))
			.andExpect(status().isBadRequest())
			.andExpect(header().string("Cache-Control", "no-store"))
			.andExpect(jsonPath("$.data.code").value("TRAIN_SEARCH_INVALID_ARGUMENT"));

		mockMvc.perform(get("/api/v1/trains/search").param("departureDate", "not-a-date"))
			.andExpect(status().isBadRequest())
			.andExpect(header().string("Cache-Control", "no-store"))
			.andExpect(jsonPath("$.data.code").value("TRAIN_SEARCH_INVALID_ARGUMENT"));

		for (var expected : List.of(
			new ExpectedFailure("TRAIN_SEARCH_INVALID_ARGUMENT", 422),
			new ExpectedFailure("TRAIN_SEARCH_NO_VALID_ROWS", 502),
			new ExpectedFailure("TRAIN_SEARCH_PROVIDER_ERROR", 502),
			new ExpectedFailure("TRAIN_SEARCH_UNAVAILABLE", 503)
		)) {
			TrainSearchFailure failure = new TrainSearchFailure(expected.code());
			doThrow(failure).when(service).searchWithMetadata(any());
			mockMvc.perform(get("/api/v1/trains/search")
					.param("departureStationId", "NAT010000")
					.param("arrivalStationId", "NAT011668")
					.param("departureDate", "2026-07-20")
					.param("trainType", "KTX"))
				.andExpect(status().is(expected.status()))
				.andExpect(header().string("Cache-Control", "no-store"))
				.andExpect(jsonPath("$.data.code").value(expected.code()));
		}
	}

	private SearchResult result() {
		return new SearchResult(
			OffsetDateTime.parse("2026-07-19T09:00:00+09:00"),
			List.of(new Journey(
				"101", "KTX", "NAT010000", "서울",
				OffsetDateTime.parse("2026-07-19T09:00:00+09:00"),
				"NAT011668", "대전", OffsetDateTime.parse("2026-07-19T10:02:00+09:00"),
				62, 23_700
			)),
			List.of()
		);
	}

	private record ExpectedFailure(String code, int status) {}
}
