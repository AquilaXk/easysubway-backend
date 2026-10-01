package com.easysubway.transit.adapter.out.seoul;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import com.easysubway.transit.adapter.out.persistence.JdbcFacilityOperationalStatusRepository;
import com.easysubway.transit.application.port.out.FacilityOperationalStatusStore;
import com.easysubway.transit.application.port.out.FacilityOperationalStatusStore.FeedApplyResult;
import com.easysubway.transit.application.port.out.FacilityOperationalStatusStore.FeedObservation;
import com.easysubway.transit.domain.FacilityOperationalState;
import com.easysubway.transit.domain.FacilityOperationalStatus;
import com.easysubway.transit.domain.FacilityStatusSource;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

@DisplayName("서울교통공사 엘리베이터 가동 정보 수집기")
class SeoulMetroElevatorStatusCollectorTest {

	private static final String FEED = "SEOUL_METRO_ELEVATOR";
	private static final Instant NOW = Instant.parse("2026-09-30T01:00:00Z");
	private static final String PAGE_ONE_ROWS = """
		[
		  {"stnCd":"0201","stnNm":"가역","lineNm":"2호선","dtlPstn":"1번 출입구","oprtngSitu":"M"},
		  {"stnCd":"0201","stnNm":"가역","lineNm":"2호선","dtlPstn":"2번 출입구","oprtngSitu":"S"},
		  {"stnCd":"0201","stnNm":"가역","lineNm":"2호선","dtlPstn":"대합실","oprtngSitu":"M"},
		  {"stnCd":"0201","stnNm":"가역","lineNm":"2호선","dtlPstn":"3번 출입구","oprtngSitu":"D"},
		  {"stnCd":"0201","stnNm":"가역","lineNm":"2호선","dtlPstn":"4번 출입구","oprtngSitu":"X"}
		]
		""";

	private final ObjectMapper objectMapper = new ObjectMapper();
	private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
	private final MutableClock clock = new MutableClock(NOW);
	private final RecordingStore store = new RecordingStore();
	private final List<URI> requests = new CopyOnWriteArrayList<>();
	private HttpServer server;

	@AfterEach
	void stopServer() {
		if (server != null) {
			server.stop(0);
		}
	}

	@Test
	@DisplayName("성공 수집은 식별된 시설 상태를 쓰고 심장박동·코드별·식별 불가 지표를 남긴다")
	void successfulCollectionWritesStatusesHeartbeatAndMetrics() throws IOException {
		startServer(exchange -> respond(exchange, 200, page(PAGE_ONE_ROWS, 5)));
		var collector = collector("test-key");

		collector.collect();

		assertThat(store.applied).containsExactly(new AppliedCollection(FEED, List.of(
			new FeedObservation("smrt-elev:0201:2:1번 출입구", FacilityOperationalState.OPERATING, "M"),
			new FeedObservation("smrt-elev:0201:2:2번 출입구", FacilityOperationalState.OUT_OF_SERVICE, "S")
		), NOW));
		assertThat(requests).hasSize(1);
		assertThat(query(requests.getFirst())).containsExactly(
			Map.entry("serviceKey", "test-key"),
			Map.entry("pageNo", "1"),
			Map.entry("numOfRows", "1000"),
			Map.entry("dataType", "JSON")
		);
		assertThat(requests.getFirst().getPath()).isEqualTo("/B553766/facility/getFcElvtr");
		assertThat(counter("success", "NONE")).isEqualTo(1.0);
		assertThat(gauge("easysubway.facility_status.feed.facilities", "code", "M")).isEqualTo(2.0);
		assertThat(gauge("easysubway.facility_status.feed.facilities", "code", "S")).isEqualTo(1.0);
		assertThat(gauge("easysubway.facility_status.feed.facilities", "code", "D")).isEqualTo(1.0);
		assertThat(gauge("easysubway.facility_status.feed.facilities", "code", "UNKNOWN")).isEqualTo(1.0);
		assertThat(gauge("easysubway.facility_status.feed.facilities", "code", "T")).isZero();
		assertThat(gauge("easysubway.facility_status.feed.unidentifiable", "reason", "LOCATION_FORMAT")).isEqualTo(1.0);
		assertThat(gauge("easysubway.facility_status.feed.unidentifiable", "reason", "DUPLICATE")).isZero();
		assertThat(gauge("easysubway.facility_status.feed.unidentifiable", "reason", "LINE_OR_STATION_CODE")).isZero();
		assertThat(gauge("easysubway.facility_status.feed.admin_verified_kept", null, null)).isEqualTo(1.0);
		assertThat(gauge("easysubway.facility_status.feed.admin_verified_absent", null, null)).isEqualTo(2.0);
		assertThat(meterRegistry.get("easysubway.facility_status.feed.unknown_code").tag("feed", FEED).counter().count())
			.isEqualTo(1.0);
		assertThat(gauge("easysubway.facility_status.feed.seconds_since_last_success", null, null)).isZero();

		clock.advance(Duration.ofSeconds(90));

		assertThat(gauge("easysubway.facility_status.feed.seconds_since_last_success", null, null)).isEqualTo(90.0);
	}

	@Test
	@DisplayName("여러 페이지를 모두 받은 뒤에만 한 번에 반영한다")
	void collectsAllPagesBeforeApplying() throws IOException {
		startServer(exchange -> {
			String pageNo = query(exchange.getRequestURI()).get("pageNo");
			String row = "[{\"stnCd\":\"0201\",\"stnNm\":\"가역\",\"lineNm\":\"2호선\",\"dtlPstn\":\"" + pageNo
				+ "번 출입구\",\"oprtngSitu\":\"M\"}]";
			respond(exchange, 200, page(row, 2));
		});
		var collector = collector("test-key", 1, 1_048_576);

		collector.collect();

		assertThat(requests).extracting(uri -> query(uri).get("pageNo")).containsExactly("1", "2");
		assertThat(store.applied).singleElement()
			.extracting(AppliedCollection::observations)
			.isEqualTo(List.of(
				new FeedObservation("smrt-elev:0201:2:1번 출입구", FacilityOperationalState.OPERATING, "M"),
				new FeedObservation("smrt-elev:0201:2:2번 출입구", FacilityOperationalState.OPERATING, "M")
			));
	}

	@Test
	@DisplayName("다음 성공 회차에서 D(삭제)가 된 시설과 목록에서 빠진 시설의 원천 행은 지우고, 관리자 확인 행은 남긴다")
	void deletedOrRemovedFacilitiesLoseFeedRowsButKeepAdminRows() throws IOException {
		var repository = h2Repository();
		List<String> bodies = new ArrayList<>(List.of(
			page("""
				[
				  {"stnCd":"0201","stnNm":"가역","lineNm":"2호선","dtlPstn":"1번 출입구","oprtngSitu":"S"},
				  {"stnCd":"0201","stnNm":"가역","lineNm":"2호선","dtlPstn":"2번 출입구","oprtngSitu":"T"},
				  {"stnCd":"0201","stnNm":"가역","lineNm":"2호선","dtlPstn":"5번 출입구","oprtngSitu":"M"},
				  {"stnCd":"0201","stnNm":"가역","lineNm":"2호선","dtlPstn":"6번 출입구","oprtngSitu":"I"}
				]
				""", 4),
			page("""
				[
				  {"stnCd":"0201","stnNm":"가역","lineNm":"2호선","dtlPstn":"1번 출입구","oprtngSitu":"D"},
				  {"stnCd":"0201","stnNm":"가역","lineNm":"2호선","dtlPstn":"5번 출입구","oprtngSitu":"M"}
				]
				""", 2)
		));
		startServer(exchange -> respond(exchange, 200, bodies.removeFirst()));
		var collector = new SeoulMetroElevatorStatusCollector(
			"test-key", endpoint(), repository, objectMapper, meterRegistry, httpClient(), clock, 1000, 1_048_576
		);

		collector.collect();
		assertThat(repository.recordAdminVerified(
			"smrt-elev:0201:2:6번 출입구", FacilityOperationalState.OUT_OF_SERVICE, NOW.plusSeconds(30)
		).recorded()).isTrue();
		clock.advance(Duration.ofSeconds(60));
		collector.collect();

		assertThat(repository.loadStatuses()).extracting(FacilityOperationalStatus::facilityId, FacilityOperationalStatus::source)
			.containsExactly(
				tuple("smrt-elev:0201:2:5번 출입구", FacilityStatusSource.SEOUL_METRO_FEED),
				tuple("smrt-elev:0201:2:6번 출입구", FacilityStatusSource.ADMIN_VERIFIED)
			);
		assertThat(repository.lastSuccessfulCollectionAt(FEED)).contains(NOW.plusSeconds(60));
		assertThat(gauge("easysubway.facility_status.feed.admin_verified_absent", null, null)).isEqualTo(1.0);
	}

	@Test
	@DisplayName("일부 페이지만 받은 회차는 원천 행을 하나도 지우지 않는다")
	void partialCollectionRemovesNothing() throws IOException {
		var repository = h2Repository();
		List<Integer> pageTwoStatuses = new ArrayList<>(List.of(200, 503));
		startServer(exchange -> {
			String pageNo = query(exchange.getRequestURI()).get("pageNo");
			int status = "2".equals(pageNo) ? pageTwoStatuses.removeFirst() : 200;
			String row = "[{\"stnCd\":\"0201\",\"stnNm\":\"가역\",\"lineNm\":\"2호선\",\"dtlPstn\":\"" + pageNo
				+ "번 출입구\",\"oprtngSitu\":\"S\"}]";
			respond(exchange, status, page(row, 2));
		});
		var collector = new SeoulMetroElevatorStatusCollector(
			"test-key", endpoint(), repository, objectMapper, meterRegistry, httpClient(), clock, 1, 1_048_576
		);

		collector.collect();
		clock.advance(Duration.ofSeconds(60));
		collector.collect();

		assertThat(repository.loadStatuses()).extracting(FacilityOperationalStatus::facilityId)
			.containsExactly("smrt-elev:0201:2:1번 출입구", "smrt-elev:0201:2:2번 출입구");
		assertThat(repository.lastSuccessfulCollectionAt(FEED)).contains(NOW);
		assertThat(counter("failure", "HTTP_STATUS")).isEqualTo(1.0);
	}

	@Test
	@DisplayName("HTTP 오류는 그 회차를 반영하지 않고 심장박동도 기록하지 않는다")
	void httpErrorDoesNotApplyOrAdvanceHeartbeat() throws IOException {
		startServer(exchange -> respond(exchange, 503, "unavailable"));
		var collector = collector("test-key");

		collector.collect();

		assertFailedWithoutApplying("HTTP_STATUS");
		assertThat(gauge("easysubway.facility_status.feed.seconds_since_last_success", null, null)).isNaN();
	}

	@Test
	@DisplayName("원천 결과 코드 오류는 그 회차를 반영하지 않는다")
	void providerResultCodeErrorDoesNotApply() throws IOException {
		startServer(exchange -> respond(exchange, 200,
			"{\"response\":{\"header\":{\"resultCode\":\"30\",\"resultMsg\":\"SERVICE_KEY_IS_NOT_REGISTERED_ERROR\"}}}"));
		var collector = collector("test-key");

		collector.collect();

		assertFailedWithoutApplying("PROVIDER_RESULT_CODE");
	}

	@Test
	@DisplayName("응답 형식 오류(JSON·items·totalCount·빈 목록·페이지 부족·초과·행 형식·본문 상한)는 그 회차를 반영하지 않는다")
	void malformedResponsesDoNotApply() throws IOException {
		List<String> bodies = List.of(
			"not-json",
			"{\"response\":{\"header\":{\"resultCode\":\"00\"},\"body\":{\"items\":{\"item\":{}},\"totalCount\":1}}}",
			"{\"response\":{\"header\":{\"resultCode\":\"00\"},\"body\":{\"items\":{\"item\":[]},\"totalCount\":\"x\"}}}",
			"{\"response\":{\"header\":{\"resultCode\":\"00\"},\"body\":{\"items\":{\"item\":[]},\"totalCount\":-1}}}",
			page("[]", 0),
			page("[]", 3),
			page("[{\"stnCd\":\"0201\",\"stnNm\":\"가역\",\"lineNm\":\"2호선\",\"dtlPstn\":\"1번 출입구\",\"oprtngSitu\":\"M\"},"
				+ "{\"stnCd\":\"0201\",\"stnNm\":\"가역\",\"lineNm\":\"2호선\",\"dtlPstn\":\"2번 출입구\",\"oprtngSitu\":\"M\"}]", 1),
			page("[{\"stnCd\":\"0201\",\"lineNm\":\"2호선\",\"dtlPstn\":\"1번 출입구\",\"oprtngSitu\":\"M\"}]", 1),
			page(PAGE_ONE_ROWS, 5) + " ".repeat(64)
		);
		List<String> remaining = new ArrayList<>(bodies);
		startServer(exchange -> respond(exchange, 200, remaining.removeFirst()));
		var collector = collector("test-key", 1000, page(PAGE_ONE_ROWS, 5).getBytes(StandardCharsets.UTF_8).length + 32);

		for (int attempt = 0; attempt < bodies.size(); attempt++) {
			collector.collect();
		}

		assertThat(store.applied).isEmpty();
		assertThat(counter("failure", "MALFORMED_RESPONSE")).isEqualTo(bodies.size());
		assertThat(counter("success", "NONE")).isZero();
	}

	@Test
	@DisplayName("페이지마다 totalCount가 다르면 응답 형식 오류다")
	void inconsistentTotalCountAcrossPagesIsMalformed() throws IOException {
		startServer(exchange -> {
			boolean first = "1".equals(query(exchange.getRequestURI()).get("pageNo"));
			respond(exchange, 200, page(
				"[{\"stnCd\":\"0201\",\"stnNm\":\"가역\",\"lineNm\":\"2호선\",\"dtlPstn\":\"1번 출입구\",\"oprtngSitu\":\"M\"}]",
				first ? 2 : 3
			));
		});
		var collector = collector("test-key", 1, 1_048_576);

		collector.collect();

		assertFailedWithoutApplying("MALFORMED_RESPONSE");
	}

	@Test
	@DisplayName("연결 실패는 전송 오류로 드러내고 반영하지 않는다")
	void transportFailureDoesNotApply() throws IOException {
		startServer(exchange -> respond(exchange, 200, page(PAGE_ONE_ROWS, 5)));
		URI endpoint = endpoint();
		server.stop(0);
		server = null;
		var collector = new SeoulMetroElevatorStatusCollector(
			"test-key", endpoint, store, objectMapper, meterRegistry, httpClient(), clock, 1000, 1_048_576
		);

		collector.collect();

		assertFailedWithoutApplying("TRANSPORT");
	}

	@Test
	@DisplayName("저장 실패는 심장박동 없이 실패로 센다")
	void storeFailureIsCountedAsFailure() throws IOException {
		startServer(exchange -> respond(exchange, 200, page(PAGE_ONE_ROWS, 5)));
		store.failWith = new IllegalStateException("db down");
		var collector = collector("test-key");

		collector.collect();

		assertThat(counter("failure", "STORE_WRITE")).isEqualTo(1.0);
		assertThat(counter("success", "NONE")).isZero();
		assertThat(gauge("easysubway.facility_status.feed.seconds_since_last_success", null, null)).isNaN();
	}

	@Test
	@DisplayName("실패 회차는 직전 성공 지표를 새 관측처럼 덮어쓰지 않고 경과 시간만 늘어난다")
	void failureAfterSuccessKeepsLastSuccessTime() throws IOException {
		List<Integer> statuses = new ArrayList<>(List.of(200, 500));
		startServer(exchange -> respond(exchange, statuses.removeFirst(), page(PAGE_ONE_ROWS, 5)));
		var collector = collector("test-key");

		collector.collect();
		clock.advance(Duration.ofSeconds(60));
		collector.collect();

		assertThat(store.applied).hasSize(1);
		assertThat(counter("success", "NONE")).isEqualTo(1.0);
		assertThat(counter("failure", "HTTP_STATUS")).isEqualTo(1.0);
		assertThat(gauge("easysubway.facility_status.feed.seconds_since_last_success", null, null)).isEqualTo(60.0);
	}

	@Test
	@DisplayName("인증키가 없으면 원천을 호출하지 않는다")
	void blankServiceKeySkipsCollection() throws IOException {
		startServer(exchange -> respond(exchange, 200, page(PAGE_ONE_ROWS, 5)));
		var collector = collector("  ");

		collector.collect();

		assertThat(requests).isEmpty();
		assertThat(store.applied).isEmpty();
		assertThat(meterRegistry.find("easysubway.facility_status.feed.collections").counters()).isEmpty();
	}

	@Test
	@DisplayName("URL 인코딩된 인증키는 한 번만 인코딩해 보낸다")
	void encodedServiceKeyIsDecodedOnce() throws IOException {
		startServer(exchange -> respond(exchange, 200, page(PAGE_ONE_ROWS, 5)));
		var collector = collector(" abc%2Bdef%3D%3D ");

		collector.collect();

		assertThat(requests.getFirst().getRawQuery()).startsWith("serviceKey=abc%2Bdef%3D%3D&");
		assertThat(query(requests.getFirst())).containsEntry("serviceKey", "abc+def==");
	}

	@Test
	@DisplayName("수집 중 인터럽트는 실패로 세고 인터럽트 상태를 되살린다")
	void interruptionIsCountedAndRestored() throws IOException {
		startServer(exchange -> respond(exchange, 200, page(PAGE_ONE_ROWS, 5)));
		var collector = collector("test-key");

		Thread.currentThread().interrupt();
		try {
			collector.collect();
			assertThat(Thread.currentThread().isInterrupted()).isTrue();
		} finally {
			Thread.interrupted();
		}

		assertFailedWithoutApplying("INTERRUPTED");
	}

	@Test
	@DisplayName("classify 도중 예상 밖의 RuntimeException이 발생하면 UNEXPECTED 실패 지표를 기록하고 심장박동은 옮기지 않는다")
	void unexpectedExceptionRecordsUnexpectedMetricAndDoesNotAdvanceHeartbeat() throws IOException {
		startServer(exchange -> respond(exchange, 200, page(PAGE_ONE_ROWS, 5)));
		var collector = new SeoulMetroElevatorStatusCollector(
			"test-key", endpoint(), store, objectMapper, meterRegistry, httpClient(), clock, 1000, 1_048_576,
			rows -> { throw new NullPointerException("simulated unexpected classifier bug"); }
		);

		collector.collect();

		assertFailedWithoutApplying("UNEXPECTED");
		assertThat(store.applied).isEmpty();
	}

	private void assertFailedWithoutApplying(String reason) {
		assertThat(store.applied).isEmpty();
		assertThat(counter("failure", reason)).isEqualTo(1.0);
		assertThat(counter("success", "NONE")).isZero();
	}

	private static JdbcFacilityOperationalStatusRepository h2Repository() {
		var dataSource = new DriverManagerDataSource(
			"jdbc:h2:mem:elevator-collector-" + UUID.randomUUID() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
			"sa",
			""
		);
		new ResourceDatabasePopulator(
			new ClassPathResource("db/migration/h2/V76__facility_operational_status.sql")
		).execute(dataSource);
		return new JdbcFacilityOperationalStatusRepository(dataSource, new DataSourceTransactionManager(dataSource));
	}

	private SeoulMetroElevatorStatusCollector collector(String serviceKey) {
		return collector(serviceKey, 1000, 1_048_576);
	}

	private SeoulMetroElevatorStatusCollector collector(String serviceKey, int pageSize, int maxResponseBytes) {
		return new SeoulMetroElevatorStatusCollector(
			serviceKey, endpoint(), store, objectMapper, meterRegistry, httpClient(), clock, pageSize, maxResponseBytes
		);
	}

	private static HttpClient httpClient() {
		return HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
	}

	private URI endpoint() {
		return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/B553766/facility/getFcElvtr");
	}

	private void startServer(Handler handler) throws IOException {
		server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/", exchange -> {
			requests.add(exchange.getRequestURI());
			handler.handle(exchange);
		});
		server.start();
	}

	private static void respond(HttpExchange exchange, int status, String body) throws IOException {
		byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
		exchange.sendResponseHeaders(status, bytes.length);
		try (var output = exchange.getResponseBody()) {
			output.write(bytes);
		}
	}

	private static String page(String items, int totalCount) {
		return "{\"response\":{\"header\":{\"resultCode\":\"00\",\"resultMsg\":\"NORMAL_CODE\"},"
			+ "\"body\":{\"items\":{\"item\":" + items + "},\"numOfRows\":1000,\"pageNo\":1,\"totalCount\":" + totalCount + "}}}";
	}

	private static Map<String, String> query(URI uri) {
		Map<String, String> values = new LinkedHashMap<>();
		for (String pair : uri.getRawQuery().split("&")) {
			String[] parts = pair.split("=", 2);
			values.put(parts[0], URLDecoder.decode(parts[1], StandardCharsets.UTF_8));
		}
		return values;
	}

	private double counter(String outcome, String reason) {
		var counter = meterRegistry.find("easysubway.facility_status.feed.collections")
			.tag("feed", FEED).tag("outcome", outcome).tag("reason", reason).counter();
		return counter == null ? 0.0 : counter.count();
	}

	private double gauge(String name, String tagKey, String tagValue) {
		var search = meterRegistry.get(name).tag("feed", FEED);
		if (tagKey != null) {
			search = search.tag(tagKey, tagValue);
		}
		return search.gauge().value();
	}

	@FunctionalInterface
	private interface Handler {
		void handle(HttpExchange exchange) throws IOException;
	}

	private record AppliedCollection(String feed, List<FeedObservation> observations, Instant observedAt) {
	}

	private static final class RecordingStore implements FacilityOperationalStatusStore {

		private final List<AppliedCollection> applied = new ArrayList<>();
		private RuntimeException failWith;

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
			if (failWith != null) {
				throw failWith;
			}
			applied.add(new AppliedCollection(feed, List.copyOf(observations), observedAt));
			return new FeedApplyResult(observations.size() - 1, 1, 3, 2);
		}

		@Override
		public AdminVerifiedResult recordAdminVerified(String facilityId, FacilityOperationalState state, Instant verifiedAt) {
			return new AdminVerifiedResult(false, Optional.empty(), Optional.empty());
		}
	}

	private static final class MutableClock extends Clock {

		private Instant now;

		private MutableClock(Instant now) {
			this.now = now;
		}

		private void advance(Duration duration) {
			now = now.plus(duration);
		}

		@Override
		public ZoneId getZone() {
			return ZoneOffset.UTC;
		}

		@Override
		public Clock withZone(ZoneId zone) {
			return this;
		}

		@Override
		public Instant instant() {
			return now;
		}
	}
}
