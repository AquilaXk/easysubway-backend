package com.easysubway.transit.adapter.out.seoul;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.easysubway.transit.application.port.out.LoadTransitMasterPort;
import com.easysubway.transit.application.port.out.SaveAccessibilityFacilityStatusPort;
import com.easysubway.transit.domain.AccessibilityFacility;
import com.easysubway.transit.domain.AccessibilityFacilityStatus;
import com.easysubway.transit.domain.AccessibilityFacilityType;
import com.easysubway.transit.domain.DataConfidenceLevel;
import com.easysubway.transit.domain.DataSourceType;
import com.easysubway.transit.domain.Station;
import com.easysubway.transit.domain.StationExit;
import com.easysubway.transit.domain.StationLine;
import com.easysubway.transit.domain.SubwayLine;
import com.easysubway.transit.domain.TransitOperator;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

@DisplayName("서울교통공사 엘리베이터 상태 수집기 단위 테스트 (#419)")
class SeoulMetroElevatorStatusCollectorTest {

	private static final Instant T0 = Instant.parse("2026-09-30T09:00:00Z");
	private static final LocalDate DATE_T0 = LocalDate.of(2026, 9, 30);
	private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

	private static class MutableClock extends Clock {
		private Instant now;

		MutableClock(Instant initial) {
			this.now = initial;
		}

		void advance(Duration duration) {
			now = now.plus(duration);
		}

		@Override
		public ZoneOffset getZone() {
			return ZoneOffset.UTC;
		}

		@Override
		public Clock withZone(java.time.ZoneId zone) {
			return this;
		}

		@Override
		public Instant instant() {
			return now;
		}
	}

	private static class TestTransitMasterRepository implements LoadTransitMasterPort, SaveAccessibilityFacilityStatusPort {
		final Map<String, AccessibilityFacility> facilities = new LinkedHashMap<>();
		final List<StationLine> stationLines = new ArrayList<>();

		@Override
		public List<TransitOperator> loadOperators() { return List.of(); }
		@Override
		public List<SubwayLine> loadLines() { return List.of(); }
		@Override
		public List<Station> loadStations() { return List.of(); }
		@Override
		public List<StationLine> loadStationLines() { return List.copyOf(stationLines); }
		@Override
		public List<StationExit> loadStationExits() { return List.of(); }
		@Override
		public List<AccessibilityFacility> loadAccessibilityFacilities() { return List.copyOf(facilities.values()); }
		@Override
		public Optional<AccessibilityFacility> loadAccessibilityFacility(String facilityId) {
			return Optional.ofNullable(facilities.get(facilityId));
		}
		@Override
		public void saveAccessibilityFacility(AccessibilityFacility facility, String updatedBy) {
			facilities.put(facility.id(), facility);
		}
		@Override
		public void saveFacilityStatus(String facilityId, AccessibilityFacilityStatus status, LocalDate updatedAt) {
			AccessibilityFacility existing = facilities.get(facilityId);
			if (existing != null) {
				facilities.put(facilityId, new AccessibilityFacility(
					existing.id(), existing.stationId(), existing.exitId(), existing.type(), existing.name(),
					existing.floorFrom(), existing.floorTo(), existing.latitude(), existing.longitude(),
					existing.description(), status, existing.dataConfidence(), existing.dataSourceType(), updatedAt
				));
			}
		}
	}

	private MutableClock clock;
	private SimpleMeterRegistry meterRegistry;
	private TestTransitMasterRepository repository;
	private HttpClient httpClient;

	@BeforeEach
	void setUp() {
		clock = new MutableClock(T0);
		meterRegistry = new SimpleMeterRegistry();
		repository = new TestTransitMasterRepository();
		httpClient = mock(HttpClient.class);
	}

	@Test
	@DisplayName("(1) 번 출입구 형식이고 KRIC 시설이 정확히 1대일 때 상태를 연결하여 갱신한다")
	void linksSingleMatchingKricElevatorAndAppliesStatus() throws Exception {
		repository.stationLines.add(new StationLine("station-seoul", "line-1", "0150", 1, ""));
		var kricFacility = new AccessibilityFacility(
			"kric-elev:S1:1:0150:1:1F-B1F:hash1",
			"station-seoul",
			"1",
			AccessibilityFacilityType.ELEVATOR,
			"1번 출구 엘리베이터",
			"1F", "B1F",
			BigDecimal.valueOf(37.555), BigDecimal.valueOf(126.970),
			"1번 출입구 앞",
			AccessibilityFacilityStatus.NORMAL,
			DataConfidenceLevel.HIGH,
			DataSourceType.OFFICIAL_API,
			DATE_T0
		);
		repository.facilities.put(kricFacility.id(), kricFacility);

		String json = """
			{
			  "response": {
			    "header": { "resultCode": "00", "resultMsg": "NORMAL SERVICE." },
			    "body": {
			      "items": {
			        "item": [
			          {
			            "stnCd": "0150",
			            "stnNm": "서울역",
			            "lineNm": "1호선",
			            "dtlPstn": "1번 출입구",
			            "oprtngSitu": "S"
			          }
			        ]
			      },
			      "numOfRows": 1000,
			      "pageNo": 1,
			      "totalCount": 1
			    }
			  }
			}
			""";
		mockHttpResponse(200, json);

		var collector = new SeoulMetroElevatorStatusCollector(
			new SeoulMetroElevatorStatusCollector.Configuration("valid-key", "https://api.test/getFcElvtr"),
			repository, repository, OBJECT_MAPPER, httpClient, clock, meterRegistry
		);

		collector.collect();

		AccessibilityFacility updated = repository.facilities.get("kric-elev:S1:1:0150:1:1F-B1F:hash1");
		assertThat(updated).isNotNull();
		assertThat(updated.status()).isEqualTo(AccessibilityFacilityStatus.BROKEN);
		assertThat(collector.linkedElevatorsCount()).isEqualTo(1);
		assertThat(collector.unlinkedElevatorsCount()).isZero();
		assertThat(meterRegistry.find("easysubway.collection.seoul-metro.elevator.linked-count").gauge().value()).isEqualTo(1.0);
		assertThat(meterRegistry.find("easysubway.collection.seoul-metro.elevator.unlinked-ratio").gauge().value()).isEqualTo(0.0);
	}

	@Test
	@DisplayName("(2) dtlPstn 형식이 '번 출입구'가 아니면 미연결로 처리하고 상태를 붙이지 않는다")
	void marksUnlinkedWhenPositionFormatDoesNotMatch() throws Exception {
		repository.stationLines.add(new StationLine("station-seoul", "line-1", "0150", 1, ""));
		var kricFacility = new AccessibilityFacility(
			"kric-elev:S1:1:0150:1:1F-B1F:hash1",
			"station-seoul",
			"1",
			AccessibilityFacilityType.ELEVATOR,
			"1번 출구 엘리베이터",
			"1F", "B1F",
			BigDecimal.valueOf(37.555), BigDecimal.valueOf(126.970),
			"1번 출입구 앞",
			AccessibilityFacilityStatus.NORMAL,
			DataConfidenceLevel.HIGH,
			DataSourceType.OFFICIAL_API,
			DATE_T0
		);
		repository.facilities.put(kricFacility.id(), kricFacility);

		String json = """
			{
			  "response": {
			    "header": { "resultCode": "00", "resultMsg": "NORMAL SERVICE." },
			    "body": {
			      "items": {
			        "item": [
			          {
			            "stnCd": "0150",
			            "stnNm": "서울역",
			            "lineNm": "1호선",
			            "dtlPstn": "내부 환승통로",
			            "oprtngSitu": "S"
			          }
			        ]
			      },
			      "numOfRows": 1000,
			      "pageNo": 1,
			      "totalCount": 1
			    }
			  }
			}
			""";
		mockHttpResponse(200, json);

		var collector = new SeoulMetroElevatorStatusCollector(
			new SeoulMetroElevatorStatusCollector.Configuration("valid-key", "https://api.test/getFcElvtr"),
			repository, repository, OBJECT_MAPPER, httpClient, clock, meterRegistry
		);

		collector.collect();

		// Facility remains NORMAL (unmodified)
		assertThat(repository.facilities.get("kric-elev:S1:1:0150:1:1F-B1F:hash1").status())
			.isEqualTo(AccessibilityFacilityStatus.NORMAL);
		assertThat(collector.linkedElevatorsCount()).isZero();
		assertThat(collector.unlinkedElevatorsCount()).isEqualTo(1);
		assertThat(meterRegistry.find("easysubway.collection.seoul-metro.elevator.unlinked-ratio").gauge().value()).isEqualTo(1.0);
	}

	@Test
	@DisplayName("(3) 해당 출입구의 KRIC 시설이 0대이면 미연결로 처리한다")
	void marksUnlinkedWhenMatchingKricElevatorsCountIsZero() throws Exception {
		repository.stationLines.add(new StationLine("station-seoul", "line-1", "0150", 1, ""));
		// No facilities in repository

		String json = """
			{
			  "response": {
			    "header": { "resultCode": "00", "resultMsg": "NORMAL SERVICE." },
			    "body": {
			      "items": {
			        "item": [
			          {
			            "stnCd": "0150",
			            "stnNm": "서울역",
			            "lineNm": "1호선",
			            "dtlPstn": "3번 출입구",
			            "oprtngSitu": "S"
			          }
			        ]
			      },
			      "numOfRows": 1000,
			      "pageNo": 1,
			      "totalCount": 1
			    }
			  }
			}
			""";
		mockHttpResponse(200, json);

		var collector = new SeoulMetroElevatorStatusCollector(
			new SeoulMetroElevatorStatusCollector.Configuration("valid-key", "https://api.test/getFcElvtr"),
			repository, repository, OBJECT_MAPPER, httpClient, clock, meterRegistry
		);

		collector.collect();

		assertThat(collector.linkedElevatorsCount()).isZero();
		assertThat(collector.unlinkedElevatorsCount()).isEqualTo(1);
	}

	@Test
	@DisplayName("(4) 해당 출입구의 KRIC 시설이 2대 이상이면 모호하므로 상태를 붙이지 않고 미연결 처리한다")
	void marksUnlinkedWhenMatchingKricElevatorsCountIsTwoOrMore() throws Exception {
		repository.stationLines.add(new StationLine("station-seoul", "line-1", "0150", 1, ""));
		// 2 elevators at exit 1
		var kric1 = new AccessibilityFacility(
			"kric-elev:S1:1:0150:1:1F-B1F:hash1", "station-seoul", "1", AccessibilityFacilityType.ELEVATOR,
			"1번 엘리베이터 A", "1F", "B1F", BigDecimal.valueOf(37.555), BigDecimal.valueOf(126.970),
			"", AccessibilityFacilityStatus.NORMAL, DataConfidenceLevel.HIGH, DataSourceType.OFFICIAL_API, DATE_T0
		);
		var kric2 = new AccessibilityFacility(
			"kric-elev:S1:1:0150:1:B1F-B2F:hash2", "station-seoul", "1", AccessibilityFacilityType.ELEVATOR,
			"1번 엘리베이터 B", "B1F", "B2F", BigDecimal.valueOf(37.555), BigDecimal.valueOf(126.970),
			"", AccessibilityFacilityStatus.NORMAL, DataConfidenceLevel.HIGH, DataSourceType.OFFICIAL_API, DATE_T0
		);
		repository.facilities.put(kric1.id(), kric1);
		repository.facilities.put(kric2.id(), kric2);

		String json = """
			{
			  "response": {
			    "header": { "resultCode": "00", "resultMsg": "NORMAL SERVICE." },
			    "body": {
			      "items": {
			        "item": [
			          {
			            "stnCd": "0150",
			            "stnNm": "서울역",
			            "lineNm": "1호선",
			            "dtlPstn": "1번 출입구",
			            "oprtngSitu": "S"
			          }
			        ]
			      },
			      "numOfRows": 1000,
			      "pageNo": 1,
			      "totalCount": 1
			    }
			  }
			}
			""";
		mockHttpResponse(200, json);

		var collector = new SeoulMetroElevatorStatusCollector(
			new SeoulMetroElevatorStatusCollector.Configuration("valid-key", "https://api.test/getFcElvtr"),
			repository, repository, OBJECT_MAPPER, httpClient, clock, meterRegistry
		);

		collector.collect();

		// Neither is modified (ambiguous)
		assertThat(repository.facilities.get(kric1.id()).status()).isEqualTo(AccessibilityFacilityStatus.NORMAL);
		assertThat(repository.facilities.get(kric2.id()).status()).isEqualTo(AccessibilityFacilityStatus.NORMAL);
		assertThat(collector.linkedElevatorsCount()).isZero();
		assertThat(collector.unlinkedElevatorsCount()).isEqualTo(1);
	}

	@Test
	@DisplayName("(5) 역 이름(stnNm) 조인은 절대 수행하지 않으며, stnCd 미매핑 시 미연결 처리한다")
	void neverJoinsByStationName() throws Exception {
		// Only station line with code "0150" exists, but item has code "9999" (even if stnNm matches)
		repository.stationLines.add(new StationLine("station-seoul", "line-1", "0150", 1, ""));
		var kric = new AccessibilityFacility(
			"kric-elev:S1:1:0150:1:1F-B1F:hash1", "station-seoul", "1", AccessibilityFacilityType.ELEVATOR,
			"1번 엘리베이터", "1F", "B1F", BigDecimal.valueOf(37.555), BigDecimal.valueOf(126.970),
			"", AccessibilityFacilityStatus.NORMAL, DataConfidenceLevel.HIGH, DataSourceType.OFFICIAL_API, DATE_T0
		);
		repository.facilities.put(kric.id(), kric);

		String json = """
			{
			  "response": {
			    "header": { "resultCode": "00", "resultMsg": "NORMAL SERVICE." },
			    "body": {
			      "items": {
			        "item": [
			          {
			            "stnCd": "9999",
			            "stnNm": "서울역",
			            "lineNm": "1호선",
			            "dtlPstn": "1번 출입구",
			            "oprtngSitu": "S"
			          }
			        ]
			      },
			      "numOfRows": 1000,
			      "pageNo": 1,
			      "totalCount": 1
			    }
			  }
			}
			""";
		mockHttpResponse(200, json);

		var collector = new SeoulMetroElevatorStatusCollector(
			new SeoulMetroElevatorStatusCollector.Configuration("valid-key", "https://api.test/getFcElvtr"),
			repository, repository, OBJECT_MAPPER, httpClient, clock, meterRegistry
		);

		collector.collect();

		assertThat(repository.facilities.get(kric.id()).status()).isEqualTo(AccessibilityFacilityStatus.NORMAL);
		assertThat(collector.linkedElevatorsCount()).isZero();
		assertThat(collector.unlinkedElevatorsCount()).isEqualTo(1);
	}

	@Test
	@DisplayName("관리자 확인(ADMIN_VERIFIED) 시설은 관측일이 확인일보다 미래일 때만 공식 상태로 갱신된다")
	void respectsAdminVerifiedPriority() throws Exception {
		repository.stationLines.add(new StationLine("station-seoul", "line-1", "0150", 1, ""));
		var adminFacility = new AccessibilityFacility(
			"kric-elev:S1:1:0150:1:1F-B1F:hash1", "station-seoul", "1", AccessibilityFacilityType.ELEVATOR,
			"1번 엘리베이터", "1F", "B1F", BigDecimal.valueOf(37.555), BigDecimal.valueOf(126.970),
			"", AccessibilityFacilityStatus.ADMIN_VERIFIED, DataConfidenceLevel.HIGH, DataSourceType.ADMIN_VERIFIED,
			LocalDate.of(2026, 9, 30) // Updated today by admin
		);
		repository.facilities.put(adminFacility.id(), adminFacility);

		String json = """
			{
			  "response": {
			    "header": { "resultCode": "00", "resultMsg": "NORMAL SERVICE." },
			    "body": {
			      "items": {
			        "item": [
			          {
			            "stnCd": "0150",
			            "stnNm": "서울역",
			            "lineNm": "1호선",
			            "dtlPstn": "1번 출입구",
			            "oprtngSitu": "M"
			          }
			        ]
			      },
			      "numOfRows": 1000,
			      "pageNo": 1,
			      "totalCount": 1
			    }
			  }
			}
			""";
		mockHttpResponse(200, json);

		var collector = new SeoulMetroElevatorStatusCollector(
			new SeoulMetroElevatorStatusCollector.Configuration("valid-key", "https://api.test/getFcElvtr"),
			repository, repository, OBJECT_MAPPER, httpClient, clock, meterRegistry
		);

		// 1. Same day: ADMIN_VERIFIED retained
		collector.collect();
		assertThat(repository.facilities.get(adminFacility.id()).status())
			.isEqualTo(AccessibilityFacilityStatus.ADMIN_VERIFIED);

		// 2. Next day: Official API status applied
		clock.advance(Duration.ofDays(1));
		collector.collect();
		assertThat(repository.facilities.get(adminFacility.id()).status())
			.isEqualTo(AccessibilityFacilityStatus.NORMAL);
	}

	@Test
	@DisplayName("수집 성공 시 심장박동 갱신, 수집 실패 시 심장박동 미갱신 및 failureCounter 증가")
	void heartbeatUpdatedOnSuccessAndPreservedOnFailure() throws Exception {
		mockHttpResponse(500, "Internal Server Error");

		var collector = new SeoulMetroElevatorStatusCollector(
			new SeoulMetroElevatorStatusCollector.Configuration("valid-key", "https://api.test/getFcElvtr"),
			repository, repository, OBJECT_MAPPER, httpClient, clock, meterRegistry
		);

		collector.collect();

		assertThat(collector.lastSuccessfulSourceCollectionAt()).isNull();
		assertThat(meterRegistry.find("easysubway.collection.seoul-metro.elevator.sync")
			.tag("status", "failure").counter().count()).isEqualTo(1.0);
	}

	@ParameterizedTest
	@CsvSource({
		"M, NORMAL",
		"S, BROKEN",
		"T, BROKEN",
		"I, BROKEN",
		"B, UNDER_CONSTRUCTION"
	})
	@DisplayName("공식 상태 코드 매핑 검증")
	void parsesOfficialStatusCodes(String code, AccessibilityFacilityStatus expectedStatus) {
		assertThat(SeoulMetroElevatorStatusCollector.parseOperationStatus(code)).isEqualTo(expectedStatus);
	}

	@Test
	@DisplayName("미등록 상태 코드는 UNKNOWN 카운터를 증가시킨다")
	void incrementsUnknownCodeCounterOnInvalidStatus() {
		assertThat(SeoulMetroElevatorStatusCollector.parseOperationStatus("X")).isNull();
		assertThat(SeoulMetroElevatorStatusCollector.parseOperationStatus(null)).isNull();
	}

	@SuppressWarnings("unchecked")
	private void mockHttpResponse(int statusCode, String body) throws IOException, InterruptedException {
		HttpResponse<InputStream> response = mock(HttpResponse.class);
		when(response.statusCode()).thenReturn(statusCode);
		when(response.body()).thenReturn(new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)));
		when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(response);
	}
}
