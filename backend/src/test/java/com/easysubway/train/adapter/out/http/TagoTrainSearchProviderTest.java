package com.easysubway.train.adapter.out.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.easysubway.train.application.TrainSearchProvider.ProviderFailure;
import com.easysubway.train.domain.TrainSearchModels.Journey;
import com.easysubway.train.domain.TrainSearchModels.LegQuery;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class TagoTrainSearchProviderTest {

	private static final ObjectMapper JSON = new ObjectMapper();

	@Test
	void removesItxCheongchunButKeepsDaejeonKtx() throws Exception {
		var provider = new TagoTrainSearchProvider(
			"never-print-service-key",
			JSON,
			HttpClient.newHttpClient(),
			Clock.fixed(Instant.parse("2026-07-19T00:00:00Z"), ZoneOffset.UTC),
			URI.create("http://127.0.0.1/")
		);
		var query = new LegQuery(
			"NAT010000",
			"NAT011668",
			LocalDate.parse("2026-07-20"),
			null
		);

		var journeys = provider.parseJourneys(JSON.readTree("""
			{"response":{"header":{"resultCode":"00","resultMsg":"NORMAL SERVICE."},"body":{
			  "items":{"item":[
			    {"trainno":"00101","traingradename":"KTX","depplandtime":"20260720090000","arrplandtime":"20260720100200","depplacename":"서울","arrplacename":"대전","adultcharge":23700},
			    {"trainno":"2001","traingradename":"ITX-청춘","depplandtime":"20260720091000","arrplandtime":"20260720110000","depplacename":"서울","arrplacename":"대전","adultcharge":"10000"}
			  ]},"pageNo":1,"numOfRows":100,"totalCount":2
			}}}
			"""), query);

		assertThat(journeys)
			.extracting(Journey::trainType, Journey::trainNumber, Journey::adultFareWon, Journey::durationMinutes)
			.containsExactly(tuple("KTX", "101", 23_700, 62));
		assertThat(journeys.getFirst().departureStationId()).isEqualTo("NAT010000");
		assertThat(journeys.getFirst().arrivalStationId()).isEqualTo("NAT011668");
	}

	@Test
	void loadsNonPaginatedCatalogOperationsAndPaginatedStations() throws Exception {
		var requests = new ConcurrentHashMap<String, Map<String, String>>();
		var server = server((exchange) -> {
			String operation = exchange.getRequestURI().getPath().substring(1);
			requests.put(operation, query(exchange.getRequestURI()));
			switch (operation) {
				case "GetCtyCodeList" -> respond(exchange, catalogResponse("""
					[{"citycode":"11","cityname":"서울"}]
					"""));
				case "GetVhcleKndList" -> respond(exchange, catalogResponse("""
					[
					  {"vehiclekndid":"00","vehiclekndnm":"KTX"},
					  {"vehiclekndid":"01","vehiclekndnm":"KTX-산천"},
					  {"vehiclekndid":"10","vehiclekndnm":"KTX-산천"},
					  {"vehiclekndid":"02","vehiclekndnm":"SRT"},
					  {"vehiclekndid":"03","vehiclekndnm":"ITX-마음"},
					  {"vehiclekndid":"04","vehiclekndnm":"ITX-새마을"},
					  {"vehiclekndid":"05","vehiclekndnm":"새마을호"},
					  {"vehiclekndid":"06","vehiclekndnm":"무궁화호"},
					  {"vehiclekndid":"08","vehiclekndnm":"누리로"},
					  {"vehiclekndid":"07","vehiclekndnm":"ITX-청춘"}
					]
					"""));
				case "GetCtyAcctoTrainSttnList" -> respond(exchange, paginatedResponse("""
					[
					  {"nodeid":"NAT010000","nodename":"서울"},
					  {"nodeid":"NAT011668","nodename":"대전"}
					]
					""", 2));
				default -> respond(exchange, 404, "{}");
			}
		});
		try {
			var provider = provider(server, "encoded%2Bservice%2Fkey");

			var catalog = provider.catalog();

			assertThat(catalog.observedAt()).isEqualTo(Instant.parse("2026-07-19T00:00:00Z"));
			assertThat(catalog.stations()).extracting(station -> station.name())
				.containsExactly("대전", "서울");
			assertThat(catalog.trainTypes()).extracting(type -> type.code())
				.containsExactly(
					"ITX_MAUM", "ITX_SAEMAEUL", "KTX", "KTX_SANCHEON",
					"MUGUNGHWA", "NURIRO", "SAEMAEUL", "SRT"
				);
			assertThat(catalog.trainTypes())
				.filteredOn(type -> "KTX_SANCHEON".equals(type.code()))
				.singleElement()
				.extracting(type -> type.providerCodes())
				.isEqualTo(java.util.List.of("01", "10"));
			assertThat(requests.get("GetCtyCodeList")).doesNotContainKeys("pageNo", "numOfRows");
			assertThat(requests.get("GetVhcleKndList")).doesNotContainKeys("pageNo", "numOfRows");
			assertThat(requests.get("GetCtyAcctoTrainSttnList"))
				.containsEntry("cityCode", "11")
				.containsEntry("pageNo", "1")
				.containsEntry("numOfRows", "100")
				.containsEntry("serviceKey", "encoded+service/key");
		} finally {
			server.stop(0);
		}
	}

	@Test
	void rejectsConflictingStationNamesForOneProviderId() throws Exception {
		var server = server(exchange -> {
			String operation = exchange.getRequestURI().getPath().substring(1);
			switch (operation) {
				case "GetCtyCodeList" -> respond(exchange, catalogResponse("""
					[{"citycode":"11","cityname":"서울"},{"citycode":"12","cityname":"부산"}]
					"""));
				case "GetVhcleKndList" -> respond(exchange, catalogResponse("""
					[
					  {"vehiclekndid":"00","vehiclekndnm":"KTX"},
					  {"vehiclekndid":"01","vehiclekndnm":"KTX-산천"},
					  {"vehiclekndid":"02","vehiclekndnm":"SRT"},
					  {"vehiclekndid":"03","vehiclekndnm":"ITX-마음"},
					  {"vehiclekndid":"04","vehiclekndnm":"ITX-새마을"},
					  {"vehiclekndid":"05","vehiclekndnm":"새마을호"},
					  {"vehiclekndid":"06","vehiclekndnm":"무궁화호"},
					  {"vehiclekndid":"08","vehiclekndnm":"누리로"}
					]
					"""));
				case "GetCtyAcctoTrainSttnList" -> {
					String cityCode = query(exchange.getRequestURI()).get("cityCode");
					String name = "11".equals(cityCode) ? "서울" : "서울역";
					respond(exchange, paginatedResponse("""
						[{"nodeid":"NAT010000","nodename":"%s"}]
						""".formatted(name), 1));
				}
				default -> respond(exchange, 404, "unexpected operation");
			}
		});
		try {
			assertThatThrownBy(() -> provider(server, "test-key").catalog())
				.isInstanceOf(ProviderFailure.class)
				.hasMessage("TRAIN_SEARCH_PROVIDER_ERROR");
		} finally {
			server.stop(0);
		}
	}

	@Test
	void searchesWithNormalizedQueryAndDoesNotExposeSecretOnProviderFailure() throws Exception {
		var requests = new ConcurrentHashMap<String, Map<String, String>>();
		var requestedDates = ConcurrentHashMap.<String>newKeySet();
		var server = server((exchange) -> {
			String operation = exchange.getRequestURI().getPath().substring(1);
			requests.put(operation, query(exchange.getRequestURI()));
			if ("GetStrtpntAlocFndTrainInfo".equals(operation)) {
				requestedDates.add(query(exchange.getRequestURI()).get("depPlandTime"));
				if (respondEmptyForNextDay(exchange)) return;
				respond(exchange, paginatedResponse("""
					[{"trainno":"101","traingradename":"KTX","depplandtime":"20260720090000","arrplandtime":"20260720100200","depplacename":"서울","arrplacename":"대전","adultcharge":"23700"}]
					""", 1));
				return;
			}
			respond(exchange, 500, "secret must not escape");
		});
		try {
			var provider = provider(server, "never-print-service-key");
			var query = legQuery(LocalDate.parse("2026-07-20"), "KTX", "00");

			assertThat(provider.search(query)).hasSize(1);
			assertThat(requestedDates).containsExactlyInAnyOrder("20260720", "20260721");
			assertThat(requests.get("GetStrtpntAlocFndTrainInfo"))
				.containsEntry("depPlaceId", "NAT010000")
				.containsEntry("arrPlaceId", "NAT011668")
				.containsEntry("trainGradeCode", "00")
				.containsEntry("pageNo", "1")
				.containsEntry("numOfRows", "100");

			var failing = provider(server, "literal-secret-value");
			assertThatThrownBy(failing::catalog)
				.isInstanceOf(ProviderFailure.class)
				.hasMessage("TRAIN_SEARCH_PROVIDER_ERROR")
				.hasMessageNotContaining("literal-secret-value")
				.hasMessageNotContaining("secret must not escape");
		} finally {
			server.stop(0);
		}
	}

	@Test
	void searchesEveryProviderCodeForOneCanonicalTrainType() throws Exception {
		var requestedCodes = ConcurrentHashMap.<String>newKeySet();
		var server = server(exchange -> {
			if (respondEmptyForNextDay(exchange)) return;
			String providerCode = query(exchange.getRequestURI()).get("trainGradeCode");
			requestedCodes.add(providerCode);
			String trainNumber = "01".equals(providerCode) ? "101" : "102";
			respond(exchange, paginatedResponse("""
				{"trainno":"%s","traingradename":"KTX-산천","depplandtime":"20260720090000","arrplandtime":"20260720100200","depplacename":"서울","arrplacename":"대전","adultcharge":"23700"}
				""".formatted(trainNumber), 1));
		});
		try {
			var query = new LegQuery(
				"NAT010000",
				"NAT011668",
				LocalDate.parse("2026-07-20"),
				"KTX_SANCHEON",
				java.util.List.of("01", "10"),
				"서울",
				"대전"
			);

			assertThat(provider(server, "test-key").search(query))
				.extracting(Journey::trainNumber)
				.containsExactly("101", "102");
			assertThat(requestedCodes).containsExactlyInAnyOrder("01", "10");
		} finally {
			server.stop(0);
		}
	}

	@Test
	void rejectsDuplicateRowsWithinOneProviderCode() throws Exception {
		String duplicate = journeyRow(101);
		var server = server(exchange -> {
			if (respondEmptyForNextDay(exchange)) return;
			respond(exchange, paginatedResponse("[" + duplicate + "," + duplicate + "]", 2));
		});
		try {
			assertThatThrownBy(() -> provider(server, "test-key")
				.search(legQuery(LocalDate.parse("2026-07-20"), "KTX", "00")))
				.isInstanceOf(ProviderFailure.class)
				.hasMessage("TRAIN_SEARCH_PROVIDER_ERROR");
		} finally {
			server.stop(0);
		}
	}

	@Test
	void rejectsConflictingCopiesAcrossProviderCodes() throws Exception {
		var server = server(exchange -> {
			if (respondEmptyForNextDay(exchange)) return;
			String providerCode = query(exchange.getRequestURI()).get("trainGradeCode");
			String fare = "01".equals(providerCode) ? "23700" : "23800";
			respond(exchange, paginatedResponse("""
				{"trainno":"101","traingradename":"KTX-산천","depplandtime":"20260720090000","arrplandtime":"20260720100200","depplacename":"서울","arrplacename":"대전","adultcharge":"%s"}
				""".formatted(fare), 1));
		});
		try {
			var query = new LegQuery(
				"NAT010000", "NAT011668", LocalDate.parse("2026-07-20"), "KTX_SANCHEON",
				java.util.List.of("01", "10"), "서울", "대전"
			);

			assertThatThrownBy(() -> provider(server, "test-key").search(query))
				.isInstanceOf(ProviderFailure.class)
				.hasMessage("TRAIN_SEARCH_PROVIDER_ERROR");
		} finally {
			server.stop(0);
		}
	}

	@Test
	void mergesPagesAndAcceptsSingleItemShape() throws Exception {
		var requestedPages = new java.util.concurrent.CopyOnWriteArrayList<String>();
		var budgetCalls = new AtomicInteger();
		var server = server(exchange -> {
			if (respondEmptyForNextDay(exchange)) {
				requestedPages.add("1");
				return;
			}
			Map<String, String> parameters = query(exchange.getRequestURI());
			requestedPages.add(parameters.get("pageNo"));
			int page = Integer.parseInt(parameters.get("pageNo"));
			String items = page == 1 ? journeyRows(1, 100) : journeyRow(101);
			respond(exchange, paginatedResponse(items, 101, page));
		});
		try {
			var provider = provider(server, "test-key", budgetCalls::incrementAndGet);
			var query = legQuery(LocalDate.parse("2026-07-20"), "KTX", "00");

			assertThat(provider.search(query)).hasSize(101);
			assertThat(requestedPages).containsExactly("1", "2", "1");
			assertThat(budgetCalls).hasValue(3);
		} finally {
			server.stop(0);
		}
	}

	@Test
	void retriesOneTransportFailureBeforeResponse() throws Exception {
		var attempts = new AtomicInteger();
		var budgetCalls = new AtomicInteger();
		var httpClient = mock(HttpClient.class);
		@SuppressWarnings("unchecked")
		var response = (HttpResponse<String>) mock(HttpResponse.class);
		@SuppressWarnings("unchecked")
		var emptyResponse = (HttpResponse<String>) mock(HttpResponse.class);
		when(response.statusCode()).thenReturn(200);
		when(response.body()).thenReturn(paginatedResponse("""
			{"trainno":"101","traingradename":"KTX","depplandtime":"20260720090000","arrplandtime":"20260720100200","depplacename":"서울","arrplacename":"대전","adultcharge":"23700"}
			""", 1));
		when(emptyResponse.statusCode()).thenReturn(200);
		when(emptyResponse.body()).thenReturn(paginatedResponse("[]", 0));
		when(httpClient.<String>send(any(HttpRequest.class), any()))
			.thenAnswer(invocation -> {
			if (attempts.incrementAndGet() == 1) {
				throw new IOException("transient transport failure");
			}
			HttpRequest request = invocation.getArgument(0);
			return request.uri().getRawQuery().contains("depPlandTime=20260721") ? emptyResponse : response;
		});
		var provider = new TagoTrainSearchProvider(
			"test-key",
			JSON,
			httpClient,
			Clock.fixed(Instant.parse("2026-07-19T00:00:00Z"), ZoneOffset.UTC),
			URI.create("https://provider.example/"),
			budgetCalls::incrementAndGet,
			java.time.Duration.ZERO
		);
		var query = legQuery(LocalDate.parse("2026-07-20"), "KTX", "00");

		assertThat(provider.search(query)).hasSize(1);
		assertThat(attempts).hasValue(3);
		assertThat(budgetCalls).hasValue(3);
	}

	@Test
	void stopsBeforeStartingAnotherHttpCallWhenTheSearchDeadlineExpires() throws Exception {
		var attempts = new AtomicInteger();
		var httpClient = mock(HttpClient.class);
		var clock = mock(Clock.class);
		@SuppressWarnings("unchecked")
		var response = (HttpResponse<String>) mock(HttpResponse.class);
		when(response.statusCode()).thenReturn(200);
		when(response.body()).thenReturn(paginatedResponse("""
			{"trainno":"101","traingradename":"KTX","depplandtime":"20260720090000","arrplandtime":"20260720100200","depplacename":"서울","arrplacename":"대전","adultcharge":"23700"}
			""", 1));
		when(httpClient.<String>send(any(HttpRequest.class), any())).thenAnswer(invocation -> {
			attempts.incrementAndGet();
			return response;
		});
		when(clock.instant()).thenAnswer(ignored -> attempts.get() == 0
			? Instant.parse("2026-07-19T00:00:00Z")
			: Instant.parse("2026-07-19T00:00:05Z"));
		var provider = new TagoTrainSearchProvider(
			"test-key",
			JSON,
			httpClient,
			clock,
			URI.create("https://provider.example/"),
			() -> {},
			java.time.Duration.ZERO
		);

		assertThatThrownBy(() -> provider.search(
			legQuery(LocalDate.parse("2026-07-20"), "KTX", "00"),
			Instant.parse("2026-07-19T00:00:05Z")
		))
			.isInstanceOf(ProviderFailure.class)
			.hasMessage("TRAIN_SEARCH_UNAVAILABLE");
		assertThat(attempts).hasValue(1);
	}

	@Test
	void recomputesTheHttpTimeoutAfterQuotaWaiting() throws Exception {
		var attempts = new AtomicInteger();
		var now = new java.util.concurrent.atomic.AtomicReference<>(Instant.parse("2026-07-19T00:00:00Z"));
		var httpClient = mock(HttpClient.class);
		var clock = mock(Clock.class);
		when(clock.instant()).thenAnswer(ignored -> now.get());
		when(httpClient.<String>send(any(HttpRequest.class), any())).thenAnswer(invocation -> {
			attempts.incrementAndGet();
			throw new AssertionError("HTTP send must not start after the deadline");
		});
		var provider = new TagoTrainSearchProvider(
			"test-key",
			JSON,
			httpClient,
			clock,
			URI.create("https://provider.example/"),
			() -> now.set(Instant.parse("2026-07-19T00:00:05Z")),
			java.time.Duration.ZERO
		);

		assertThatThrownBy(() -> provider.search(
			legQuery(LocalDate.parse("2026-07-20"), "KTX", "00"),
			Instant.parse("2026-07-19T00:00:05Z")
		))
			.isInstanceOf(ProviderFailure.class)
			.hasMessage("TRAIN_SEARCH_UNAVAILABLE");
		assertThat(attempts).hasValue(0);
	}

	@Test
	void retriesTransientHttpStatusesOnce() throws Exception {
		for (int status : java.util.List.of(408, 429, 503)) {
			var attempts = new AtomicInteger();
			var budgetCalls = new AtomicInteger();
			var server = server(exchange -> {
				if (attempts.incrementAndGet() == 1) {
					respond(exchange, status, "temporary");
					return;
				}
				if (respondEmptyForNextDay(exchange)) return;
				respond(exchange, paginatedResponse("""
					{"trainno":"101","traingradename":"KTX","depplandtime":"20260720090000","arrplandtime":"20260720100200","depplacename":"서울","arrplacename":"대전","adultcharge":"23700"}
					""", 1));
			});
			try {
				var query = new LegQuery(
					"NAT010000", "NAT011668", LocalDate.parse("2026-07-20"), "KTX", java.util.List.of("00"),
					"서울", "대전"
				);

				assertThat(provider(server, "test-key", budgetCalls::incrementAndGet).search(query)).hasSize(1);
				assertThat(attempts).hasValue(3);
				assertThat(budgetCalls).hasValue(3);
			} finally {
				server.stop(0);
			}
		}
	}

	@Test
	void rejectsHttpFailureEvenWhenBodyLooksSuccessful() throws Exception {
		var server = server(exchange -> respond(exchange, 500, paginatedResponse("[]", 0)));
		try {
			var provider = provider(server, "literal-secret-value");
			var query = legQuery(LocalDate.parse("2026-07-20"), "KTX", "00");

			assertThatThrownBy(() -> provider.search(query))
				.isInstanceOf(ProviderFailure.class)
				.hasMessage("TRAIN_SEARCH_PROVIDER_ERROR")
				.hasMessageNotContaining("literal-secret-value");
		} finally {
			server.stop(0);
		}
	}

	@Test
	void rejectsAPageThatMakesNoProgress() throws Exception {
		var server = server(exchange -> respond(exchange, paginatedResponse("[]", 1)));
		try {
			var query = legQuery(LocalDate.parse("2026-07-20"), "KTX", "00");

			assertThatThrownBy(() -> provider(server, "test-key").search(query))
				.isInstanceOf(ProviderFailure.class)
				.hasMessage("TRAIN_SEARCH_PROVIDER_ERROR");
		} finally {
			server.stop(0);
		}
	}

	@Test
	void rejectsShiftedPaginationEvenWhenTheFinalTotalMatches() throws Exception {
		var server = server(exchange -> {
			int page = Integer.parseInt(query(exchange.getRequestURI()).get("pageNo"));
			String rows = page == 1 ? journeyRows(1, 50) : journeyRows(51, 100);
			respond(exchange, paginatedResponse(rows, 150, page));
		});
		try {
			assertThatThrownBy(() -> provider(server, "test-key")
				.search(legQuery(LocalDate.parse("2026-07-20"), "KTX", "00")))
				.isInstanceOf(ProviderFailure.class)
				.hasMessage("TRAIN_SEARCH_PROVIDER_ERROR");
		} finally {
			server.stop(0);
		}
	}

	@Test
	void rejectsPageMetadataMismatchAndTotalDrift() throws Exception {
		var wrongPage = server(exchange -> respond(exchange, paginatedResponse(journeyRow(1), 1, 2)));
		try {
			var query = legQuery(LocalDate.parse("2026-07-20"), "KTX", "00");
			assertThatThrownBy(() -> provider(wrongPage, "test-key").search(query))
				.isInstanceOf(ProviderFailure.class)
				.hasMessage("TRAIN_SEARCH_PROVIDER_ERROR");
		} finally {
			wrongPage.stop(0);
		}

		var drift = server(exchange -> {
			int page = Integer.parseInt(query(exchange.getRequestURI()).get("pageNo"));
			respond(exchange, paginatedResponse(page == 1 ? journeyRows(1, 100) : journeyRow(101), page == 1 ? 101 : 102, page));
		});
		try {
			var query = legQuery(LocalDate.parse("2026-07-20"), "KTX", "00");
			assertThatThrownBy(() -> provider(drift, "test-key").search(query))
				.isInstanceOf(ProviderFailure.class)
				.hasMessage("TRAIN_SEARCH_PROVIDER_ERROR");
		} finally {
			drift.stop(0);
		}
	}

	@Test
	void rejectsExcessiveTotalCountBeforeRequestingAnotherPage() throws Exception {
		var attempts = new AtomicInteger();
		var server = server(exchange -> {
			attempts.incrementAndGet();
			respond(exchange, paginatedResponse(journeyRow(1), 1_001));
		});
		try {
			assertThatThrownBy(() -> provider(server, "test-key")
				.search(legQuery(LocalDate.parse("2026-07-20"), "KTX", "00")))
				.isInstanceOf(ProviderFailure.class)
				.hasMessage("TRAIN_SEARCH_PROVIDER_ERROR");
			assertThat(attempts).hasValue(1);
		} finally {
			server.stop(0);
		}
	}

	@Test
	void rejectsFractionalPaginationMetadata() throws Exception {
		var server = server(exchange -> respond(exchange, """
			{"response":{"header":{"resultCode":"00"},"body":{"items":{"item":[]},"pageNo":1.9,"numOfRows":100,"totalCount":0}}}
			"""));
		try {
			assertThatThrownBy(() -> provider(server, "test-key")
				.search(legQuery(LocalDate.parse("2026-07-20"), "KTX", "00")))
				.isInstanceOf(ProviderFailure.class)
				.hasMessage("TRAIN_SEARCH_PROVIDER_ERROR");
		} finally {
			server.stop(0);
		}
	}

	@Test
	void acceptsDigitStringPaginationMetadataFromOfficialSchema() throws Exception {
		var server = server(exchange -> {
			if (respondEmptyForNextDay(exchange)) return;
			respond(exchange, """
				{"response":{"header":{"resultCode":"00"},"body":{"items":{"item":{"trainno":"101","traingradename":"KTX","depplandtime":"20260720090000","arrplandtime":"20260720100200","depplacename":"서울","arrplacename":"대전","adultcharge":"23700"}},"pageNo":"1","numOfRows":"100","totalCount":"1"}}}
				""");
		});
		try {
			assertThat(provider(server, "test-key")
				.search(legQuery(LocalDate.parse("2026-07-20"), "KTX", "00")))
				.singleElement()
				.extracting(Journey::trainNumber)
				.isEqualTo("101");
		} finally {
			server.stop(0);
		}
	}

	@Test
	void malformedJourneyIsTypedAsNoValidRows() throws Exception {
		var server = server(exchange -> respond(exchange, paginatedResponse("""
			{"trainno":"101","traingradename":"KTX","depplandtime":"not-a-time","arrplandtime":"20260720100200","depplacename":"서울","arrplacename":"대전","adultcharge":"23700"}
			""", 1)));
		try {
			var query = legQuery(LocalDate.parse("2026-07-20"), "KTX", "00");
			assertThatThrownBy(() -> provider(server, "test-key").search(query))
				.isInstanceOf(ProviderFailure.class)
				.hasMessage("TRAIN_SEARCH_NO_VALID_ROWS");
		} finally {
			server.stop(0);
		}
	}

	@Test
	void rejectsNumericJsonForTextFields() throws Exception {
		var server = server(exchange -> respond(exchange, paginatedResponse("""
			{"trainno":101,"traingradename":"KTX","depplandtime":"20260720090000","arrplandtime":"20260720100200","depplacename":"서울","arrplacename":"대전","adultcharge":"23700"}
			""", 1)));
		try {
			assertThatThrownBy(() -> provider(server, "test-key")
				.search(legQuery(LocalDate.parse("2026-07-20"), "KTX", "00")))
				.isInstanceOf(ProviderFailure.class)
				.hasMessage("TRAIN_SEARCH_NO_VALID_ROWS");
		} finally {
			server.stop(0);
		}
	}

	@Test
	void rejectsInvalidSearchContractBeforeCallingTheProvider() throws Exception {
		var requests = new AtomicInteger();
		var server = server(exchange -> {
			requests.incrementAndGet();
			respond(exchange, paginatedResponse("[]", 0));
		});
		try {
			var provider = provider(server, "test-key");
			assertThatThrownBy(() -> provider.search(null))
				.isInstanceOf(ProviderFailure.class)
				.hasMessage("TRAIN_SEARCH_PROVIDER_ERROR");

			for (LegQuery query : java.util.List.of(
				new LegQuery(null, "NAT011668", LocalDate.parse("2026-07-20"), "KTX", java.util.List.of("00"), "서울", "대전"),
				new LegQuery("NAT010000", " ", LocalDate.parse("2026-07-20"), "KTX", java.util.List.of("00"), "서울", "대전"),
				new LegQuery("NAT010000", "NAT011668", null, "KTX", java.util.List.of("00"), "서울", "대전"),
				new LegQuery("NAT010000", "NAT011668", LocalDate.parse("2026-07-20"), null, java.util.List.of("00"), "서울", "대전"),
				new LegQuery("NAT010000", "NAT011668", LocalDate.parse("2026-07-20"), "ITX_CHEONGCHUN", java.util.List.of("07"), "서울", "대전")
			)) {
				assertThatThrownBy(() -> provider.search(query))
					.isInstanceOf(ProviderFailure.class)
					.hasMessage("TRAIN_SEARCH_PROVIDER_ERROR");
			}
			assertThat(requests).hasValue(0);
		} finally {
			server.stop(0);
		}
	}

	@Test
	void rejectsNonNumericTrainNumber() throws Exception {
		var server = server(exchange -> respond(exchange, paginatedResponse("""
			{"trainno":"20O1","traingradename":"KTX","depplandtime":"20260720090000","arrplandtime":"20260720100200","depplacename":"서울","arrplacename":"대전","adultcharge":"23700"}
			""", 1)));
		try {
			var query = legQuery(LocalDate.parse("2026-07-20"), "KTX", "00");
			assertThatThrownBy(() -> provider(server, "test-key").search(query))
				.isInstanceOf(ProviderFailure.class)
				.hasMessage("TRAIN_SEARCH_NO_VALID_ROWS");
		} finally {
			server.stop(0);
		}
	}

	@Test
	void rejectsInvalidCalendarDateInsteadOfNormalizingIt() throws Exception {
		var server = server(exchange -> respond(exchange, paginatedResponse("""
			{"trainno":"101","traingradename":"KTX","depplandtime":"20260230090000","arrplandtime":"20260230100200","depplacename":"서울","arrplacename":"대전","adultcharge":"23700"}
			""", 1)));
		try {
			var query = legQuery(LocalDate.parse("2026-02-28"), "KTX", "00");
			assertThatThrownBy(() -> provider(server, "test-key").search(query))
				.isInstanceOf(ProviderFailure.class)
				.hasMessage("TRAIN_SEARCH_NO_VALID_ROWS");
		} finally {
			server.stop(0);
		}
	}

	@Test
	void rejectsJourneyOutsideRequestedDate() throws Exception {
		var server = server(exchange -> respond(exchange, paginatedResponse("""
			{"trainno":"101","traingradename":"KTX","depplandtime":"20260721090000","arrplandtime":"20260721100200","depplacename":"서울","arrplacename":"대전","adultcharge":"23700"}
			""", 1)));
		try {
			var query = legQuery(LocalDate.parse("2026-07-20"), "KTX", "00");
			assertThatThrownBy(() -> provider(server, "test-key").search(query))
				.isInstanceOf(ProviderFailure.class)
				.hasMessage("TRAIN_SEARCH_NO_VALID_ROWS");
		} finally {
			server.stop(0);
		}
	}

	@Test
	void appliesThreeAmServiceDayBoundary() throws Exception {
		var provider = new TagoTrainSearchProvider(
			"never-print-service-key",
			JSON,
			HttpClient.newHttpClient(),
			Clock.fixed(Instant.parse("2026-07-19T00:00:00Z"), ZoneOffset.UTC),
			URI.create("http://127.0.0.1/")
		);
		var query = new LegQuery(
			"NAT010000", "NAT011668", LocalDate.parse("2026-07-20"), "KTX", java.util.List.of("00"),
			"서울", "대전"
		);

		var journeys = provider.parseJourneys(JSON.readTree("""
			{"response":{"header":{"resultCode":"00"},"body":{"items":{"item":[
			  {"trainno":"100","traingradename":"KTX","depplandtime":"20260720025900","arrplandtime":"20260720040000","depplacename":"서울","arrplacename":"대전","adultcharge":"23700"},
			  {"trainno":"101","traingradename":"KTX","depplandtime":"20260720030000","arrplandtime":"20260720040200","depplacename":"서울","arrplacename":"대전","adultcharge":"23700"},
			  {"trainno":"102","traingradename":"KTX","depplandtime":"20260721025900","arrplandtime":"20260721040000","depplacename":"서울","arrplacename":"대전","adultcharge":"23700"}
			]}}}}
			"""), query);

		assertThat(journeys).extracting(Journey::trainNumber).containsExactly("101", "102");
	}

	@Test
	void fetchesNextCalendarDayToCompleteTheThreeAmServiceDay() throws Exception {
		var requestedDates = ConcurrentHashMap.<String>newKeySet();
		var server = server(exchange -> {
			String date = query(exchange.getRequestURI()).get("depPlandTime");
			requestedDates.add(date);
			String rows = "20260720".equals(date) ? """
				[{"trainno":"101","traingradename":"KTX","depplandtime":"20260720090000","arrplandtime":"20260720100200","depplacename":"서울","arrplacename":"대전","adultcharge":"23700"}]
				""" : """
				[
				  {"trainno":"102","traingradename":"KTX","depplandtime":"20260721025900","arrplandtime":"20260721040000","depplacename":"서울","arrplacename":"대전","adultcharge":"23700"},
				  {"trainno":"103","traingradename":"KTX","depplandtime":"20260721030000","arrplandtime":"20260721040200","depplacename":"서울","arrplacename":"대전","adultcharge":"23700"}
				]
				""";
			respond(exchange, paginatedResponse(rows, "20260720".equals(date) ? 1 : 2));
		});
		try {
			var journeys = provider(server, "test-key")
				.search(legQuery(LocalDate.parse("2026-07-20"), "KTX", "00"));

			assertThat(requestedDates).containsExactlyInAnyOrder("20260720", "20260721");
			assertThat(journeys).extracting(Journey::trainNumber).containsExactly("101", "102");
		} finally {
			server.stop(0);
		}
	}

	@Test
	void rejectsResponseStationNamesThatDoNotMatchTheRequestedLeg() throws Exception {
		var server = server(exchange -> respond(exchange, paginatedResponse("""
			{"trainno":"101","traingradename":"KTX","depplandtime":"20260720090000","arrplandtime":"20260720100200","depplacename":"부산","arrplacename":"대전","adultcharge":"23700"}
			""", 1)));
		try {
			var query = new LegQuery(
				"NAT010000", "NAT011668", LocalDate.parse("2026-07-20"), "KTX", java.util.List.of("00"),
				"서울", "대전"
			);

			assertThatThrownBy(() -> provider(server, "test-key").search(query))
				.isInstanceOf(ProviderFailure.class)
				.hasMessage("TRAIN_SEARCH_NO_VALID_ROWS");
		} finally {
			server.stop(0);
		}
	}

	@Test
	void rejectsJourneyWhoseTrainTypeDiffersFromRequestedGrade() throws Exception {
		var server = server(exchange -> respond(exchange, paginatedResponse("""
			{"trainno":"301","traingradename":"SRT","depplandtime":"20260720090000","arrplandtime":"20260720100200","depplacename":"서울","arrplacename":"대전","adultcharge":"23700"}
			""", 1)));
		try {
			var query = legQuery(LocalDate.parse("2026-07-20"), "KTX", "00");
			assertThatThrownBy(() -> provider(server, "test-key").search(query))
				.isInstanceOf(ProviderFailure.class)
				.hasMessage("TRAIN_SEARCH_NO_VALID_ROWS");
		} finally {
			server.stop(0);
		}
	}

	@Test
	void missingKeyAndEmptyCatalogFailClosed() throws Exception {
		var requests = new AtomicInteger();
		var server = server(exchange -> {
			requests.incrementAndGet();
			respond(exchange, catalogResponse("[]"));
		});
		try {
			assertThatThrownBy(() -> provider(server, "").catalog())
				.isInstanceOf(ProviderFailure.class)
				.hasMessage("TRAIN_SEARCH_PROVIDER_ERROR");
			assertThat(requests).hasValue(0);

			assertThatThrownBy(() -> provider(server, "test-key").catalog())
				.isInstanceOf(ProviderFailure.class)
				.hasMessage("TRAIN_SEARCH_PROVIDER_ERROR");
			assertThat(requests).hasValue(2);
		} finally {
			server.stop(0);
		}
	}

	private TagoTrainSearchProvider provider(HttpServer server, String serviceKey) {
		return provider(server, serviceKey, () -> {});
	}

	private TagoTrainSearchProvider provider(
		HttpServer server,
		String serviceKey,
		com.easysubway.train.application.TrainSearchProviderCallBudget budget
	) {
		return new TagoTrainSearchProvider(
			serviceKey,
			JSON,
			HttpClient.newHttpClient(),
			Clock.fixed(Instant.parse("2026-07-19T00:00:00Z"), ZoneOffset.UTC),
			URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/"),
			budget,
			java.time.Duration.ZERO
		);
	}

	private LegQuery legQuery(LocalDate date, String trainType, String providerCode) {
		return new LegQuery(
			"NAT010000",
			"NAT011668",
			date,
			trainType,
			java.util.List.of(providerCode),
			"서울",
			"대전"
		);
	}

	private HttpServer server(ExchangeHandler handler) throws IOException {
		var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/", exchange -> handler.handle(exchange));
		server.start();
		return server;
	}

	private String catalogResponse(String items) {
		return "{\"response\":{\"header\":{\"resultCode\":\"00\"},\"body\":{\"items\":{\"item\":"
			+ items + "}}}}";
	}

	private String paginatedResponse(String items, int totalCount) {
		return paginatedResponse(items, totalCount, 1);
	}

	private String paginatedResponse(String items, int totalCount, int pageNo) {
		return "{\"response\":{\"header\":{\"resultCode\":\"00\"},\"body\":{\"items\":{\"item\":"
			+ items + "},\"pageNo\":" + pageNo + ",\"numOfRows\":100,\"totalCount\":" + totalCount + "}}}";
	}

	private String journeyRows(int start, int count) {
		return IntStream.range(start, start + count)
			.mapToObj(this::journeyRow)
			.collect(java.util.stream.Collectors.joining(",", "[", "]"));
	}

	private String journeyRow(int trainNumber) {
		return "{\"trainno\":\"" + trainNumber
			+ "\",\"traingradename\":\"KTX\",\"depplandtime\":\"20260720090000\","
			+ "\"arrplandtime\":\"20260720100200\",\"depplacename\":\"서울\","
			+ "\"arrplacename\":\"대전\",\"adultcharge\":\"23700\"}";
	}

	private Map<String, String> query(URI uri) {
		var values = new ConcurrentHashMap<String, String>();
		if (uri.getRawQuery() == null) return values;
		Arrays.stream(uri.getRawQuery().split("&")).forEach(part -> {
			String[] pair = part.split("=", 2);
			values.put(
				URLDecoder.decode(pair[0], StandardCharsets.UTF_8),
				URLDecoder.decode(pair.length == 1 ? "" : pair[1], StandardCharsets.UTF_8)
			);
		});
		return values;
	}

	private boolean respondEmptyForNextDay(HttpExchange exchange) throws IOException {
		if (!"20260721".equals(query(exchange.getRequestURI()).get("depPlandTime"))) {
			return false;
		}
		respond(exchange, paginatedResponse("[]", 0));
		return true;
	}

	private void respond(HttpExchange exchange, String body) throws IOException {
		respond(exchange, 200, body);
	}

	private void respond(HttpExchange exchange, int status, String body) throws IOException {
		byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
		exchange.getResponseHeaders().set("Content-Type", "application/json");
		exchange.sendResponseHeaders(status, bytes.length);
		exchange.getResponseBody().write(bytes);
		exchange.close();
	}

	@FunctionalInterface
	private interface ExchangeHandler {
		void handle(HttpExchange exchange) throws IOException;
	}
}
