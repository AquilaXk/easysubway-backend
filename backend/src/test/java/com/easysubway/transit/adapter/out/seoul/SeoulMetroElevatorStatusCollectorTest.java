package com.easysubway.transit.adapter.out.seoul;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
		public void saveFacilityStatus(String facilityId, AccessibilityFacilityStatus status, LocalDate updatedAt) {
			var existing = facilities.get(facilityId);
			if (existing != null) {
				facilities.put(facilityId, new AccessibilityFacility(
					existing.id(), existing.stationId(), existing.exitId(), existing.type(),
					existing.name(), existing.floorFrom(), existing.floorTo(),
					existing.latitude(), existing.longitude(), existing.description(),
					status, existing.dataConfidence(), existing.dataSourceType(), updatedAt
				));
			}
		}

		@Override
		public void saveAccessibilityFacility(AccessibilityFacility facility) {
			facilities.put(facility.id(), facility);
		}
	}

	private MutableClock clock;
	private SimpleMeterRegistry meterRegistry;
	private TestTransitMasterRepository repository;

	@BeforeEach
	void setUp() {
		clock = new MutableClock(T0);
		meterRegistry = new SimpleMeterRegistry();
		repository = new TestTransitMasterRepository();
		repository.stationLines.add(new StationLine("station-sinjeongnegeori", "seoul-2", "0249", 49, ""));
	}

	@ParameterizedTest
	@CsvSource({
		"M, NORMAL",
		"S, BROKEN",
		"T, BROKEN",
		"I, BROKEN",
		"B, UNDER_CONSTRUCTION"
	})
	@DisplayName("공식 oprtngSitu 코드 매핑 검증 (M/S/T/I/B)")
	void codeMappingTable(String rawCode, AccessibilityFacilityStatus expectedStatus) {
		AccessibilityFacilityStatus parsed = SeoulMetroElevatorStatusCollector.parseOperationStatus(rawCode);
		assertThat(parsed).isEqualTo(expectedStatus);
	}

	@Test
	@DisplayName("알 수 없는 코드나 빈 코드는 null을 반환하고 레코드 갱신 대상에서 제외")
	void unknownCodeReturnsNull() {
		assertThat(SeoulMetroElevatorStatusCollector.parseOperationStatus("UNKNOWN")).isNull();
		assertThat(SeoulMetroElevatorStatusCollector.parseOperationStatus("X")).isNull();
		assertThat(SeoulMetroElevatorStatusCollector.parseOperationStatus("")).isNull();
		assertThat(SeoulMetroElevatorStatusCollector.parseOperationStatus(null)).isNull();
	}

	@Test
	@DisplayName("안정 식별자 facility_id 생성 규칙 (seoul:stationCode:dtlPstn:sequence)")
	void deterministicFacilityIdGeneration() {
		String id1 = SeoulMetroElevatorStatusCollector.buildFacilityId("0249", "2번 출입구", 1);
		assertThat(id1).isEqualTo("seoul:0249:2번 출입구:1");

		String id2 = SeoulMetroElevatorStatusCollector.buildFacilityId("0249", "2번 출입구", 2);
		assertThat(id2).isEqualTo("seoul:0249:2번 출입구:2");

		String idOther = SeoulMetroElevatorStatusCollector.buildFacilityId("0202", "1번 출입구", 1);
		assertThat(idOther).isEqualTo("seoul:0202:1번 출입구:1");
	}

	private SeoulMetroElevatorStatusCollector createCollector(HttpClient mockClient) {
		return new SeoulMetroElevatorStatusCollector(
			new SeoulMetroElevatorStatusCollector.Configuration("test-key", "https://apis.data.go.kr/B553766/facility/getFcElvtr"),
			repository,
			repository,
			OBJECT_MAPPER,
			mockClient,
			clock,
			meterRegistry
		);
	}

	@Test
	@DisplayName("isAdminVerified는 null 시설 객체에 대해 안전하게 false를 반환한다")
	void isAdminVerifiedHandlesNull() {
		assertThat(SeoulMetroElevatorStatusCollector.isAdminVerified(null)).isFalse();
	}

	@Test
	@DisplayName("buildFacilityId는 빈 역코드, 상세위치, 또는 1 미만의 sequence에 대해 예외를 발생시킨다")
	void buildFacilityIdValidatesInputs() {
		assertThatThrownBy(() -> SeoulMetroElevatorStatusCollector.buildFacilityId("", "pos", 1))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> SeoulMetroElevatorStatusCollector.buildFacilityId("0249", "", 1))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> SeoulMetroElevatorStatusCollector.buildFacilityId("0249", "pos", 0))
			.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	@DisplayName("수집 성공 전 seconds-since-last-success 지표는 NaN을 반환한다")
	void secondsSinceLastSuccessReturnsNaNBeforeFirstSuccess() {
		var collector = createCollector(mock(HttpClient.class));
		var secondsGauge = meterRegistry.find("easysubway.collection.seoul-metro.elevator.seconds-since-last-success").gauge();
		assertThat(secondsGauge).isNotNull();
		assertThat(secondsGauge.value()).isNaN();
	}

	@Test
	@DisplayName("단일 객체 형태의 items.item 응답도 정상 파싱된다")
	void singleObjectItemResponseParsedCorrectly() throws Exception {
		String singleObjectJson = """
			{
				"response": {
					"header": { "resultCode": "00", "resultMsg": "NORMAL SERVICE." },
					"body": {
						"items": {
							"item": {
								"stnCd": "0249", "stnNm": "신정네거리", "lineNm": "2호선",
								"dtlPstn": "1번 출입구", "oprtngSitu": "M"
							}
						},
						"numOfRows": 1, "pageNo": 1, "totalCount": 1
					}
				}
			}
			""";

		HttpClient mockClient = mockHttpClient(200, singleObjectJson);
		var collector = createCollector(mockClient);
		collector.collect();

		var facility = repository.facilities.get("seoul:0249:1번 출입구:1");
		assertThat(facility).isNotNull();
		assertThat(facility.status()).isEqualTo(AccessibilityFacilityStatus.NORMAL);
	}

	@Test
	@DisplayName("수집 중 InterruptedException 발생 시 스레드 인터럽트 플래그를 복구하고 실패 지표를 증가시킨다")
	void interruptedExceptionRestoresThreadInterrupt() throws Exception {
		HttpClient mockClient = mock(HttpClient.class);
		when(mockClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
			.thenThrow(new InterruptedException("interrupted"));

		var collector = createCollector(mockClient);
		collector.collect();

		assertThat(Thread.interrupted()).isTrue();
		assertThat(collector.lastSuccessfulSourceCollectionAt()).isNull();
		var failureCounter = meterRegistry.find("easysubway.collection.seoul-metro.elevator.sync")
			.tag("status", "failure").counter();
		assertThat(failureCounter).isNotNull();
		assertThat(failureCounter.count()).isEqualTo(1.0);
	}

	@Test
	@DisplayName("성공적인 API 응답 파싱 및 상태 반영 (D는 삭제이므로 제외되어 sequence에 영향 없음)")
	void successfulCollectionParsesAndUpdatesFacilities() throws Exception {
		String responseJson = """
			{
				"response": {
					"header": {
						"resultCode": "00",
						"resultMsg": "NORMAL SERVICE."
					},
					"body": {
						"items": {
							"item": [
								{
									"stnCd": "0249",
									"stnNm": "신정네거리",
									"lineNm": "2호선",
									"dtlPstn": "2번 출입구",
									"oprtngSitu": "M"
								},
								{
									"stnCd": "0249",
									"stnNm": "신정네거리",
									"lineNm": "2호선",
									"dtlPstn": "2번 출입구",
									"oprtngSitu": "D"
								},
								{
									"stnCd": "0249",
									"stnNm": "신정네거리",
									"lineNm": "2호선",
									"dtlPstn": "2번 출입구",
									"oprtngSitu": "S"
								},
								{
									"stnCd": "0249",
									"stnNm": "신정네거리",
									"lineNm": "2호선",
									"dtlPstn": "까치산 방면4-1",
									"oprtngSitu": "M"
								},
								{
									"stnCd": "0249",
									"stnNm": "신정네거리",
									"lineNm": "2호선",
									"dtlPstn": "양천구청 방면3-4",
									"oprtngSitu": "Z"
								}
							]
						},
						"numOfRows": 1000,
						"pageNo": 1,
						"totalCount": 5
					}
				}
			}
			""";

		HttpClient mockClient = mockHttpClient(200, responseJson);
		var collector = createCollector(mockClient);

		collector.collect();

		// Check heartbeat updated
		assertThat(collector.lastSuccessfulSourceCollectionAt()).isEqualTo(T0);

		// D is excluded; '2번 출입구' active items are M and S.
		// Item 1: seoul:0249:2번 출입구:1 -> NORMAL
		// Item 2 (originally 3rd): seoul:0249:2번 출입구:2 -> BROKEN
		var fac1 = repository.facilities.get("seoul:0249:2번 출입구:1");
		assertThat(fac1).isNotNull();
		assertThat(fac1.status()).isEqualTo(AccessibilityFacilityStatus.NORMAL);
		assertThat(fac1.dataSourceType()).isEqualTo(DataSourceType.OFFICIAL_API);

		var fac2 = repository.facilities.get("seoul:0249:2번 출입구:2");
		assertThat(fac2).isNotNull();
		assertThat(fac2.status()).isEqualTo(AccessibilityFacilityStatus.BROKEN);

		var fac3 = repository.facilities.get("seoul:0249:까치산 방면4-1:1");
		assertThat(fac3).isNotNull();
		assertThat(fac3.status()).isEqualTo(AccessibilityFacilityStatus.NORMAL);

		// 'Z' is unknown code -> record not saved/updated
		var facUnknown = repository.facilities.get("seoul:0249:양천구청 방면3-4:1");
		assertThat(facUnknown).isNull();

		// Verify unknown code metric incremented
		var unknownCounter = meterRegistry.find("easysubway.collection.seoul-metro.elevator.unknown-codes").counter();
		assertThat(unknownCounter).isNotNull();
		assertThat(unknownCounter.count()).isEqualTo(1.0);

		// Verify success metric
		var successCounter = meterRegistry.find("easysubway.collection.seoul-metro.elevator.sync")
			.tag("status", "success").counter();
		assertThat(successCounter).isNotNull();
		assertThat(successCounter.count()).isEqualTo(1.0);

		// Verify seconds since last success
		clock.advance(Duration.ofSeconds(45));
		var secondsGauge = meterRegistry.find("easysubway.collection.seoul-metro.elevator.seconds-since-last-success").gauge();
		assertThat(secondsGauge).isNotNull();
		assertThat(secondsGauge.value()).isEqualTo(45.0);
	}

	@Test
	@DisplayName("HTTP 오류 시 심장박동 미갱신 및 failure 지표 증가 (기존 데이터 유지)")
	void httpErrorDoesNotUpdateHeartbeat() throws Exception {
		HttpClient mockClient = mockHttpClient(500, "Internal Server Error");
		var collector = createCollector(mockClient);

		collector.collect();

		// Heartbeat MUST NOT be updated
		assertThat(collector.lastSuccessfulSourceCollectionAt()).isNull();

		// Failure metric incremented
		var failureCounter = meterRegistry.find("easysubway.collection.seoul-metro.elevator.sync")
			.tag("status", "failure").counter();
		assertThat(failureCounter).isNotNull();
		assertThat(failureCounter.count()).isEqualTo(1.0);
	}

	@Test
	@DisplayName("API resultCode 오류(예: 99) 시 실패로 판정하고 심장박동 미갱신")
	void apiResultCodeErrorFailsClosed() throws Exception {
		String errorJson = """
			{
				"response": {
					"header": {
						"resultCode": "99",
						"resultMsg": "SERVICE_ACCESS_DENIED_ERROR"
					}
				}
			}
			""";

		HttpClient mockClient = mockHttpClient(200, errorJson);
		var collector = createCollector(mockClient);

		collector.collect();

		assertThat(collector.lastSuccessfulSourceCollectionAt()).isNull();
		var failureCounter = meterRegistry.find("easysubway.collection.seoul-metro.elevator.sync")
			.tag("status", "failure").counter();
		assertThat(failureCounter).isNotNull();
		assertThat(failureCounter.count()).isEqualTo(1.0);
	}

	@Test
	@DisplayName("우선순위: 관리자 확인 상태(ADMIN_VERIFIED)가 오늘 또는 더 최신이면 공식 원천 M으로 덮어쓰지 않음")
	void adminVerifiedStatusPrevailsWhenNewerOrSameDate() throws Exception {
		// Existing facility was verified broken by admin today (2026-09-30)
		String facilityId = "seoul:0249:2번 출입구:1";
		repository.saveAccessibilityFacility(new AccessibilityFacility(
			facilityId,
			"station-sinjeongnegeori",
			null,
			AccessibilityFacilityType.ELEVATOR,
			"2번 출입구",
			null, null, null, null,
			"2번 출입구",
			AccessibilityFacilityStatus.BROKEN,
			DataConfidenceLevel.HIGH,
			DataSourceType.ADMIN_VERIFIED,
			DATE_T0 // today
		));

		// Official source reports "M" (사용가능) observed today
		String responseJson = """
			{
				"response": {
					"header": { "resultCode": "00", "resultMsg": "NORMAL SERVICE." },
					"body": {
						"items": {
							"item": [
								{
									"stnCd": "0249", "stnNm": "신정네거리", "lineNm": "2호선",
									"dtlPstn": "2번 출입구", "oprtngSitu": "M"
								}
							]
						},
						"numOfRows": 10, "pageNo": 1, "totalCount": 1
					}
				}
			}
			""";

		HttpClient mockClient = mockHttpClient(200, responseJson);
		var collector = createCollector(mockClient);

		collector.collect();

		// Priority: Admin verified status (BROKEN) must PREVAIL!
		var facility = repository.facilities.get(facilityId);
		assertThat(facility.status()).isEqualTo(AccessibilityFacilityStatus.BROKEN);
		assertThat(facility.dataSourceType()).isEqualTo(DataSourceType.ADMIN_VERIFIED);
	}

	@Test
	@DisplayName("우선순위(반대 방향): 공식 관측일이 관리자 확인일보다 엄격히 이후이면 공식 원천이 갱신")
	void officialObservationUpdatesWhenStrictlyAfterAdminDate() throws Exception {
		// Existing facility was verified broken by admin yesterday (2026-09-29)
		String facilityId = "seoul:0249:2번 출입구:1";
		repository.saveAccessibilityFacility(new AccessibilityFacility(
			facilityId,
			"station-sinjeongnegeori",
			null,
			AccessibilityFacilityType.ELEVATOR,
			"2번 출입구",
			null, null, null, null,
			"2번 출입구",
			AccessibilityFacilityStatus.BROKEN,
			DataConfidenceLevel.HIGH,
			DataSourceType.ADMIN_VERIFIED,
			DATE_T0.minusDays(1) // yesterday
		));

		// Official source reports "M" (사용가능) observed today (2026-09-30)
		String responseJson = """
			{
				"response": {
					"header": { "resultCode": "00", "resultMsg": "NORMAL SERVICE." },
					"body": {
						"items": {
							"item": [
								{
									"stnCd": "0249", "stnNm": "신정네거리", "lineNm": "2호선",
									"dtlPstn": "2번 출입구", "oprtngSitu": "M"
								}
							]
						},
						"numOfRows": 10, "pageNo": 1, "totalCount": 1
					}
				}
			}
			""";

		HttpClient mockClient = mockHttpClient(200, responseJson);
		var collector = createCollector(mockClient);

		collector.collect();

		// Because observation date (2026-09-30) is after admin date (2026-09-29), official source updates it!
		var facility = repository.facilities.get(facilityId);
		assertThat(facility.status()).isEqualTo(AccessibilityFacilityStatus.NORMAL);
		assertThat(facility.dataSourceType()).isEqualTo(DataSourceType.OFFICIAL_API);
		assertThat(facility.lastUpdatedAt()).isEqualTo(DATE_T0);
	}

	@SuppressWarnings("unchecked")
	private static HttpClient mockHttpClient(int statusCode, String responseBody) throws Exception {
		HttpClient httpClient = mock(HttpClient.class);
		HttpResponse<InputStream> response = (HttpResponse<InputStream>) mock(HttpResponse.class);
		when(response.statusCode()).thenReturn(statusCode);
		when(response.body()).thenAnswer(ignored -> new ByteArrayInputStream(responseBody.getBytes(StandardCharsets.UTF_8)));
		when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
			.thenReturn(response);
		return httpClient;
	}
}
