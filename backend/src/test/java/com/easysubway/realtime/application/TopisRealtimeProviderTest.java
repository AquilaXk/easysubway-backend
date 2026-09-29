package com.easysubway.realtime.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.easysubway.realtime.application.port.out.RealtimeMappingPort;
import com.easysubway.realtime.domain.RealtimeArrival;
import com.easysubway.realtime.domain.RealtimeMapping;
import com.easysubway.realtime.domain.RealtimeStatus;
import com.easysubway.realtime.domain.RealtimeTrainPosition;
import com.easysubway.realtime.domain.RealtimeTripMapping;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSession;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.config.LoggerConfig;
import org.apache.logging.log4j.core.config.Property;
import org.apache.logging.log4j.core.layout.PatternLayout;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

@DisplayName("TOPIS 실시간 원천 응답 fail-closed 해석")
class TopisRealtimeProviderTest {

	private static final ObjectMapper JSON = new ObjectMapper();
	private static final RealtimeQuery EULJIRO_3GA_LINE_3_ARRIVALS =
		new RealtimeQuery("station-euljiro-3ga", "seoul-3", "1003", "을지로3가", null);
	private static final RealtimeQuery LINE_3_POSITIONS =
		new RealtimeQuery(null, "seoul-3", "1003", null, "3호선");
	private static final String ONE_ARRIVAL_PAYLOAD = """
		{"errorMessage":{"status":200,"code":"INFO-000","message":"정상 처리되었습니다."},\
		"realtimeArrivalList":[{"subwayId":"1003","statnNm":"을지로3가","trainLineNm":"오금행 - 충무로방면",\
		"updnLine":"하행","btrainNo":"3007","barvlDt":"120","arvlMsg2":"2분 후","arvlMsg3":"종로3가",\
		"recptnDt":"2026-09-29 08:00:00"}]}""";

	@Test
	@DisplayName("환승역 도착 응답에서 subwayId가 없거나 빈 항목은 질의 노선으로 채우지 않고 버린다")
	void arrivalWithoutSubwayIdIsDroppedInsteadOfAttributedToQueriedLine() {
		ChunkedBodyHttpClient httpClient = ChunkedBodyHttpClient.ok("""
			{
			  "errorMessage": {"status": 200, "code": "INFO-000", "message": "정상 처리되었습니다."},
			  "realtimeArrivalList": [
			    {"statnNm": "을지로3가", "trainLineNm": "오금행 - 충무로방면", "updnLine": "하행",
			     "btrainNo": "3001", "barvlDt": "120", "arvlMsg2": "2분 후", "recptnDt": "2026-09-29 08:00:00"},
			    {"subwayId": " ", "statnNm": "을지로3가", "trainLineNm": "대화행 - 종로3가방면", "updnLine": "상행",
			     "btrainNo": "3002", "barvlDt": "180", "arvlMsg2": "3분 후", "recptnDt": "2026-09-29 08:00:00"},
			    {"subwayId": "1002", "statnNm": "을지로3가", "trainLineNm": "성수행 - 을지로4가방면", "updnLine": "내선",
			     "btrainNo": "2001", "barvlDt": "60", "arvlMsg2": "1분 후", "recptnDt": "2026-09-29 08:00:00"}
			  ]
			}
			""");

		List<RealtimeArrival> arrivals = provider(httpClient).arrivals(EULJIRO_3GA_LINE_3_ARRIVALS);

		assertThat(arrivals)
			.extracting(RealtimeArrival::lineId, RealtimeArrival::stationName, RealtimeArrival::trainNo)
			.containsExactly(tuple("1002", "을지로3가", "2001"));
	}

	@Test
	@DisplayName("도착 항목에 statnNm이 없거나 비어 있으면 조회 역명으로 채우지 않고 버린다")
	void arrivalWithoutStationNameIsDroppedInsteadOfFilledWithQueriedStation() {
		ChunkedBodyHttpClient httpClient = ChunkedBodyHttpClient.ok("""
			{
			  "errorMessage": {"status": 200, "code": "INFO-000", "message": "정상 처리되었습니다."},
			  "realtimeArrivalList": [
			    {"subwayId": "1003", "statnNm": "을지로3가", "trainLineNm": "오금행 - 충무로방면", "updnLine": "하행",
			     "btrainNo": "3003", "barvlDt": "120", "arvlMsg2": "2분 후", "recptnDt": "2026-09-29 08:00:00"},
			    {"subwayId": "1003", "trainLineNm": "대화행 - 종로3가방면", "updnLine": "상행",
			     "btrainNo": "3004", "barvlDt": "180", "arvlMsg2": "3분 후", "recptnDt": "2026-09-29 08:00:00"},
			    {"subwayId": "1003", "statnNm": "", "trainLineNm": "대화행 - 종로3가방면", "updnLine": "상행",
			     "btrainNo": "3005", "barvlDt": "240", "arvlMsg2": "4분 후", "recptnDt": "2026-09-29 08:00:00"}
			  ]
			}
			""");

		List<RealtimeArrival> arrivals = provider(httpClient).arrivals(EULJIRO_3GA_LINE_3_ARRIVALS);

		assertThat(arrivals)
			.extracting(RealtimeArrival::lineId, RealtimeArrival::stationName, RealtimeArrival::trainNo)
			.containsExactly(tuple("1003", "을지로3가", "3003"));
	}

	@Test
	@DisplayName("열차 위치 항목에 subwayId가 없으면 질의 노선으로 채우지 않고 버린다")
	void trainPositionWithoutSubwayIdIsDroppedInsteadOfAttributedToQueriedLine() {
		ChunkedBodyHttpClient httpClient = ChunkedBodyHttpClient.ok("""
			{
			  "errorMessage": {"status": 200, "code": "INFO-000", "message": "정상 처리되었습니다."},
			  "realtimePositionList": [
			    {"statnNm": "충무로", "trainNo": "3101", "trainSttus": "1", "updnLine": "1",
			     "statnTnm": "오금", "recptnDt": "2026-09-29 08:00:00"},
			    {"subwayId": "1003", "statnNm": "동대입구", "trainNo": "3102", "trainSttus": "0", "updnLine": "0",
			     "statnTnm": "대화", "recptnDt": "2026-09-29 08:00:00"}
			  ]
			}
			""");

		List<RealtimeTrainPosition> positions = provider(httpClient).trainPositions(LINE_3_POSITIONS);

		assertThat(positions)
			.extracting(RealtimeTrainPosition::lineId, RealtimeTrainPosition::stationName, RealtimeTrainPosition::trainNo)
			.containsExactly(tuple("1003", "동대입구", "3102"));
	}

	@Test
	@DisplayName("열차 위치 항목에 statnNm이나 trainNo가 없거나 비어 있으면 빈 문자열로 채우지 않고 버린다")
	void trainPositionWithoutStationNameOrTrainNoIsDroppedInsteadOfPassingBlankPlaceholder() {
		ChunkedBodyHttpClient httpClient = ChunkedBodyHttpClient.ok("""
			{
			  "errorMessage": {"status": 200, "code": "INFO-000", "message": "정상 처리되었습니다."},
			  "realtimePositionList": [
			    {"subwayId": "1003", "trainNo": "3103", "trainSttus": "1", "updnLine": "1",
			     "statnTnm": "오금", "recptnDt": "2026-09-29 08:00:00"},
			    {"subwayId": "1003", "statnNm": " ", "trainNo": "3104", "trainSttus": "1", "updnLine": "1",
			     "statnTnm": "오금", "recptnDt": "2026-09-29 08:00:00"},
			    {"subwayId": "1003", "statnNm": "충무로", "trainSttus": "1", "updnLine": "1",
			     "statnTnm": "오금", "recptnDt": "2026-09-29 08:00:00"},
			    {"subwayId": "1003", "statnNm": "충무로", "trainNo": "", "trainSttus": "1", "updnLine": "1",
			     "statnTnm": "오금", "recptnDt": "2026-09-29 08:00:00"},
			    {"subwayId": "1003", "statnNm": "동대입구", "trainNo": "3102", "trainSttus": "0", "updnLine": "0",
			     "statnTnm": "대화", "recptnDt": "2026-09-29 08:00:00"}
			  ]
			}
			""");

		List<RealtimeTrainPosition> positions = provider(httpClient).trainPositions(LINE_3_POSITIONS);

		assertThat(positions)
			.extracting(RealtimeTrainPosition::lineId, RealtimeTrainPosition::stationName, RealtimeTrainPosition::trainNo)
			.containsExactly(tuple("1003", "동대입구", "3102"));
	}

	@Test
	@DisplayName("도착 목록이 비어 있지 않은데 필수 필드 누락으로 전부 버려지면 도착 없음이 아니라 원천 불가로 닫는다")
	void arrivalListWhoseEveryItemIsDroppedIsProviderUnavailable() {
		ChunkedBodyHttpClient httpClient = ChunkedBodyHttpClient.ok("""
			{
			  "errorMessage": {"status": 200, "code": "INFO-000", "message": "정상 처리되었습니다."},
			  "realtimeArrivalList": [
			    {"statnNm": "을지로3가", "trainLineNm": "오금행 - 충무로방면", "updnLine": "하행",
			     "btrainNo": "3001", "barvlDt": "120", "arvlMsg2": "2분 후", "recptnDt": "2026-09-29 08:00:00"},
			    {"subwayId": "1003", "trainLineNm": "대화행 - 종로3가방면", "updnLine": "상행",
			     "btrainNo": "3002", "barvlDt": "180", "arvlMsg2": "3분 후", "recptnDt": "2026-09-29 08:00:00"}
			  ]
			}
			""");

		assertThatThrownBy(() -> provider(httpClient).arrivals(EULJIRO_3GA_LINE_3_ARRIVALS))
			.isInstanceOf(RealtimeProviderException.class)
			.hasMessage("PROVIDER_UNAVAILABLE");
	}

	@Test
	@DisplayName("열차 위치 목록이 비어 있지 않은데 필수 필드 누락으로 전부 버려지면 위치 없음이 아니라 원천 불가로 닫는다")
	void positionListWhoseEveryItemIsDroppedIsProviderUnavailable() {
		ChunkedBodyHttpClient httpClient = ChunkedBodyHttpClient.ok("""
			{
			  "errorMessage": {"status": 200, "code": "INFO-000", "message": "정상 처리되었습니다."},
			  "realtimePositionList": [
			    {"statnNm": "충무로", "trainNo": "3101", "recptnDt": "2026-09-29 08:00:00"},
			    {"subwayId": "1003", "trainNo": "3102", "recptnDt": "2026-09-29 08:00:00"},
			    {"subwayId": "1003", "statnNm": "동대입구", "recptnDt": "2026-09-29 08:00:00"}
			  ]
			}
			""");

		assertThatThrownBy(() -> provider(httpClient).trainPositions(LINE_3_POSITIONS))
			.isInstanceOf(RealtimeProviderException.class)
			.hasMessage("PROVIDER_UNAVAILABLE");
	}

	@Test
	@DisplayName("일부 항목만 버려지면 유효 항목은 유지하고 버린 건수만 WARN으로 남긴다(역명·열차번호 등 원천 값은 남기지 않는다)")
	void partialDropKeepsValidItemsAndWarnsWithCountsOnly() {
		ChunkedBodyHttpClient arrivalsClient = ChunkedBodyHttpClient.ok("""
			{
			  "errorMessage": {"status": 200, "code": "INFO-000", "message": "정상 처리되었습니다."},
			  "realtimeArrivalList": [
			    {"subwayId": "1003", "statnNm": "을지로3가", "trainLineNm": "오금행 - 충무로방면", "updnLine": "하행",
			     "btrainNo": "3007", "barvlDt": "120", "arvlMsg2": "2분 후", "recptnDt": "2026-09-29 08:00:00"},
			    {"statnNm": "을지로3가", "trainLineNm": "대화행 - 종로3가방면", "updnLine": "상행",
			     "btrainNo": "3008", "barvlDt": "180", "arvlMsg2": "3분 후", "recptnDt": "2026-09-29 08:00:00"},
			    {"subwayId": "1003", "trainLineNm": "대화행 - 종로3가방면", "updnLine": "상행",
			     "btrainNo": "3009", "barvlDt": "240", "arvlMsg2": "4분 후", "recptnDt": "2026-09-29 08:00:00"}
			  ]
			}
			""");
		ChunkedBodyHttpClient positionsClient = ChunkedBodyHttpClient.ok("""
			{
			  "errorMessage": {"status": 200, "code": "INFO-000", "message": "정상 처리되었습니다."},
			  "realtimePositionList": [
			    {"subwayId": "1003", "statnNm": "동대입구", "trainNo": "3102", "trainSttus": "0", "updnLine": "0",
			     "statnTnm": "대화", "recptnDt": "2026-09-29 08:00:00"},
			    {"subwayId": "1003", "statnNm": "충무로", "trainSttus": "1", "updnLine": "1",
			     "statnTnm": "오금", "recptnDt": "2026-09-29 08:00:00"}
			  ]
			}
			""");
		List<LogEvent> warnings = new ArrayList<>();

		List<RealtimeArrival> arrivals;
		List<RealtimeTrainPosition> positions;
		try (CapturedProviderLog ignored = CapturedProviderLog.capture(warnings)) {
			arrivals = provider(arrivalsClient).arrivals(EULJIRO_3GA_LINE_3_ARRIVALS);
			positions = provider(positionsClient).trainPositions(LINE_3_POSITIONS);
		}

		assertThat(arrivals).extracting(RealtimeArrival::trainNo).containsExactly("3007");
		assertThat(positions).extracting(RealtimeTrainPosition::trainNo).containsExactly("3102");
		assertThat(warnings).extracting(LogEvent::getLevel).containsOnly(Level.WARN);
		assertThat(warnings)
			.extracting(event -> event.getMessage().getFormattedMessage())
			.satisfiesExactly(
				message -> assertThat(message).contains("ARRIVALS", "receivedCount=3", "droppedCount=2"),
				message -> assertThat(message).contains("TRAIN_POSITIONS", "receivedCount=2", "droppedCount=1")
			)
			.allSatisfy(message -> assertThat(message)
				.doesNotContain("을지로3가", "충무로", "동대입구", "3007", "3008", "3009", "3102", "backend-key"));
	}

	@Test
	@DisplayName("결과 코드가 전혀 없는 응답은 목록이 배열이어도 도착·위치 없음이 아니라 원천 불가로 닫는다")
	void missingResultCodeWithArrayListIsProviderUnavailable() {
		List<String> arrivalPayloads = List.of(
			"{\"realtimeArrivalList\": []}",
			"{\"errorMessage\": {\"status\": 200, \"message\": \"정상 처리되었습니다.\"}, \"realtimeArrivalList\": []}",
			ONE_ARRIVAL_PAYLOAD.replace("\"code\":\"INFO-000\",", "")
		);
		for (String payload : arrivalPayloads) {
			assertThatThrownBy(() -> provider(ChunkedBodyHttpClient.ok(payload)).arrivals(EULJIRO_3GA_LINE_3_ARRIVALS))
				.as(payload)
				.isInstanceOf(RealtimeProviderException.class)
				.hasMessage("PROVIDER_UNAVAILABLE");
		}
		assertThatThrownBy(() -> provider(ChunkedBodyHttpClient.ok("{\"realtimePositionList\": []}"))
			.trainPositions(LINE_3_POSITIONS))
			.isInstanceOf(RealtimeProviderException.class)
			.hasMessage("PROVIDER_UNAVAILABLE");
	}

	@Test
	@DisplayName("헤더 뒤 본문이 요청 시간 예산(1.5초) 안에 끝나지 않으면 원천 불가가 아니라 timeout으로 닫고 본문 수신을 끊는다")
	void bodyStalledPastRequestTimeoutIsProviderTimeout() throws Exception {
		StalledBody body = new StalledBody(ONE_ARRIVAL_PAYLOAD.getBytes(StandardCharsets.UTF_8), Duration.ofSeconds(6));
		HttpClient httpClient = mock(HttpClient.class);
		@SuppressWarnings("unchecked")
		HttpResponse<InputStream> response = (HttpResponse<InputStream>) mock(HttpResponse.class);
		when(response.statusCode()).thenReturn(200);
		when(response.body()).thenReturn(body);
		when(httpClient.<InputStream>send(any(HttpRequest.class), any())).thenReturn(response);
		long startedAt = System.nanoTime();

		assertThatThrownBy(() -> provider(httpClient).arrivals(EULJIRO_3GA_LINE_3_ARRIVALS))
			.isInstanceOf(RealtimeProviderException.class)
			.hasMessage("PROVIDER_TIMEOUT");
		assertThat(Duration.ofNanos(System.nanoTime() - startedAt)).isLessThan(Duration.ofSeconds(4));
		assertThat(body.closed()).isTrue();
	}

	@Test
	@DisplayName("역 도착 조회는 환승역의 다른 노선 행에 밀리지 않도록 한 번의 호출로 0~20행을 요청한다")
	void arrivalRequestAsksForRowsZeroToTwentyInOneCall() throws Exception {
		List<URI> requested = new CopyOnWriteArrayList<>();
		HttpClient httpClient = stationRowsClient(
			List.of(euljiro3gaRow("1003", "3007", "하행", "120")),
			requested
		);

		provider(httpClient).arrivals(EULJIRO_3GA_LINE_3_ARRIVALS);

		assertThat(requested).singleElement()
			.extracting(URI::getRawPath)
			.asString()
			.endsWith("/json/realtimeStationArrival/0/20/"
				+ URLEncoder.encode("을지로3가", StandardCharsets.UTF_8));
	}

	@Test
	@DisplayName("환승역 응답에서 다른 노선 행이 6행 이상 앞서도 조회 노선 행을 받아 조회 노선만 FRESH로 낸다")
	void transferStationWithSixOtherLineRowsFirstStillYieldsQueriedLineFresh() throws Exception {
		List<String> stationRows = new ArrayList<>();
		for (int index = 1; index <= 6; index++) {
			stationRows.add(euljiro3gaRow("1002", "200" + index, index % 2 == 0 ? "외선" : "내선", "6" + index));
		}
		stationRows.add(euljiro3gaRow("1003", "3007", "하행", "120"));
		stationRows.add(euljiro3gaRow("1003", "3008", "상행", "180"));
		List<URI> requested = new CopyOnWriteArrayList<>();
		RealtimeGatewayService gateway = new RealtimeGatewayService(
			provider(stationRowsClient(stationRows, requested)),
			Clock.fixed(Instant.parse("2026-09-28T23:00:10Z"), ZoneOffset.UTC),
			euljiro3gaLine3MappingPort()
		);

		RealtimeArrivalResult result = gateway.arrivals(EULJIRO_3GA_LINE_3_ARRIVALS);

		assertThat(result.status()).isEqualTo(RealtimeStatus.FRESH);
		assertThat(result.arrivals())
			.extracting(RealtimeArrival::lineId, RealtimeArrival::trainNo)
			.containsExactly(tuple("1003", "3007"), tuple("1003", "3008"));
		assertThat(requested).hasSize(1);
	}

	@Test
	@DisplayName("INFO-000인데 도착 목록이 없으면 도착 없음이 아니라 원천 불가로 닫는다")
	void successCodeWithoutArrivalListIsProviderUnavailable() {
		ChunkedBodyHttpClient httpClient = ChunkedBodyHttpClient.ok("""
			{"errorMessage": {"status": 200, "code": "INFO-000", "message": "정상 처리되었습니다."}}
			""");

		assertThatThrownBy(() -> provider(httpClient).arrivals(EULJIRO_3GA_LINE_3_ARRIVALS))
			.isInstanceOf(RealtimeProviderException.class)
			.hasMessage("PROVIDER_UNAVAILABLE");
	}

	@Test
	@DisplayName("INFO-000인데 열차 위치 목록이 없으면 위치 없음이 아니라 원천 불가로 닫는다")
	void successCodeWithoutPositionListIsProviderUnavailable() {
		ChunkedBodyHttpClient httpClient = ChunkedBodyHttpClient.ok("""
			{"errorMessage": {"status": 200, "code": "INFO-000", "message": "정상 처리되었습니다."}}
			""");

		assertThatThrownBy(() -> provider(httpClient).trainPositions(LINE_3_POSITIONS))
			.isInstanceOf(RealtimeProviderException.class)
			.hasMessage("PROVIDER_UNAVAILABLE");
	}

	@Test
	@DisplayName("상태 코드가 없고 도착 목록이 배열이 아니면 원천 불가로 닫는다")
	void missingResultCodeWithNonArrayArrivalListIsProviderUnavailable() {
		ChunkedBodyHttpClient httpClient = ChunkedBodyHttpClient.ok("""
			{"realtimeArrivalList": {"subwayId": "1003", "statnNm": "을지로3가", "btrainNo": "3006",
			 "barvlDt": "120", "arvlMsg2": "2분 후", "recptnDt": "2026-09-29 08:00:00"}}
			""");

		assertThatThrownBy(() -> provider(httpClient).arrivals(EULJIRO_3GA_LINE_3_ARRIVALS))
			.isInstanceOf(RealtimeProviderException.class)
			.hasMessage("PROVIDER_UNAVAILABLE");
	}

	@Test
	@DisplayName("최상위 오류 envelope(INFO-100 인증키 오류)는 도착 없음이 아니라 PROVIDER_AUTH_REJECTED로 닫는다")
	void topLevelErrorEnvelopeIsProviderUnavailable() {
		ChunkedBodyHttpClient httpClient = ChunkedBodyHttpClient.ok("""
			{"status": 500, "code": "INFO-100", "message": "인증키가 유효하지 않습니다.",
			 "link": "", "developerMessage": "", "total": 0}
			""");

		assertThatThrownBy(() -> provider(httpClient).arrivals(EULJIRO_3GA_LINE_3_ARRIVALS))
			.isInstanceOf(RealtimeProviderException.class)
			.hasMessage("PROVIDER_AUTH_REJECTED");
		assertThatThrownBy(() -> provider(httpClient).trainPositions(LINE_3_POSITIONS))
			.isInstanceOf(RealtimeProviderException.class)
			.hasMessage("PROVIDER_AUTH_REJECTED");
	}

	@ParameterizedTest(name = "결과 코드 {0}은 {1} 원인으로 분류한다")
	@CsvSource({
		"INFO-100, PROVIDER_AUTH_REJECTED",
		"ERROR-300, PROVIDER_REQUEST_REJECTED",
		"ERROR-301, PROVIDER_REQUEST_REJECTED",
		"ERROR-310, PROVIDER_REQUEST_REJECTED",
		"ERROR-331, PROVIDER_REQUEST_REJECTED",
		"ERROR-332, PROVIDER_REQUEST_REJECTED",
		"ERROR-333, PROVIDER_REQUEST_REJECTED",
		"ERROR-334, PROVIDER_REQUEST_REJECTED",
		"ERROR-335, PROVIDER_REQUEST_REJECTED",
		"ERROR-336, PROVIDER_QUOTA_EXCEEDED",
		"ERROR-337, PROVIDER_QUOTA_EXCEEDED",
		"ERROR-500, PROVIDER_UNAVAILABLE",
		"ERROR-600, PROVIDER_UNAVAILABLE",
		"ERROR-601, PROVIDER_UNAVAILABLE",
		"UNKNOWN-999, PROVIDER_UNAVAILABLE"
	})
	@DisplayName("TOPIS 결과 코드는 원인별로 명확히 분류된다")
	void topisResultCodesAreClassified(String code, String expectedCause) {
		ChunkedBodyHttpClient httpClient = ChunkedBodyHttpClient.ok("""
			{"status": 500, "code": "%s", "message": "오류 메시지",
			 "link": "", "developerMessage": "", "total": 0}
			""".formatted(code));

		assertThatThrownBy(() -> provider(httpClient).arrivals(EULJIRO_3GA_LINE_3_ARRIVALS))
			.isInstanceOf(RealtimeProviderException.class)
			.extracting(e -> ((RealtimeProviderException) e).providerCause())
			.isEqualTo(expectedCause);
		assertThatThrownBy(() -> provider(httpClient).trainPositions(LINE_3_POSITIONS))
			.isInstanceOf(RealtimeProviderException.class)
			.extracting(e -> ((RealtimeProviderException) e).providerCause())
			.isEqualTo(expectedCause);
	}

	@Test
	@DisplayName("최상위 INFO-200 envelope(해당 데이터 없음)는 도착·위치 모두 빈 목록으로 유지한다")
	void topLevelNoDataEnvelopeStaysEmpty() {
		ChunkedBodyHttpClient httpClient = ChunkedBodyHttpClient.ok("""
			{"status": 500, "code": "INFO-200", "message": "해당하는 데이터가 없습니다.",
			 "link": "", "developerMessage": "", "total": 0}
			""");

		assertThat(provider(httpClient).arrivals(EULJIRO_3GA_LINE_3_ARRIVALS)).isEmpty();
		assertThat(provider(httpClient).trainPositions(LINE_3_POSITIONS)).isEmpty();
	}

	@Test
	@DisplayName("errorMessage INFO-200에 목록이 없으면 도착·위치 모두 빈 목록으로 유지한다")
	void nestedNoDataCodeWithoutListStaysEmpty() {
		ChunkedBodyHttpClient httpClient = ChunkedBodyHttpClient.ok("""
			{"errorMessage": {"status": 200, "code": "INFO-200", "message": "해당하는 데이터가 없습니다."}}
			""");

		assertThat(provider(httpClient).arrivals(EULJIRO_3GA_LINE_3_ARRIVALS)).isEmpty();
		assertThat(provider(httpClient).trainPositions(LINE_3_POSITIONS)).isEmpty();
	}

	@Test
	@DisplayName("응답 본문이 1 MiB를 1바이트라도 넘으면 앞부분만 잘라 파싱하지 않고 원천 불가로 닫는다")
	void bodyOneByteOverLimitIsRejectedWithoutParsingTruncatedPrefix() {
		ChunkedBodyHttpClient httpClient = new ChunkedBodyHttpClient(200, paddedBody(ONE_ARRIVAL_PAYLOAD, 1_048_577));

		assertThatThrownBy(() -> provider(httpClient).arrivals(EULJIRO_3GA_LINE_3_ARRIVALS))
			.isInstanceOf(RealtimeProviderException.class)
			.hasMessage("PROVIDER_UNAVAILABLE");
	}

	@Test
	@DisplayName("응답 본문이 정확히 1 MiB면 정상 파싱한다")
	void bodyExactlyAtLimitIsParsed() {
		ChunkedBodyHttpClient httpClient = new ChunkedBodyHttpClient(200, paddedBody(ONE_ARRIVAL_PAYLOAD, 1_048_576));

		List<RealtimeArrival> arrivals = provider(httpClient).arrivals(EULJIRO_3GA_LINE_3_ARRIVALS);

		assertThat(arrivals)
			.extracting(
				RealtimeArrival::lineId,
				RealtimeArrival::stationName,
				RealtimeArrival::trainNo,
				RealtimeArrival::etaSeconds
			)
			.containsExactly(tuple("1003", "을지로3가", "3007", 120));
	}

	@Test
	@DisplayName("과대 응답은 본문을 끝까지 수신하지 않고 중단한 뒤 원천 불가로 닫는다")
	void oversizedBodyIsNotReadToTheEnd() {
		ChunkedBodyHttpClient httpClient = new ChunkedBodyHttpClient(200, paddedBody(ONE_ARRIVAL_PAYLOAD, 4_194_304));

		assertThatThrownBy(() -> provider(httpClient).arrivals(EULJIRO_3GA_LINE_3_ARRIVALS))
			.isInstanceOf(RealtimeProviderException.class)
			.hasMessage("PROVIDER_UNAVAILABLE");
		assertThat(httpClient.deliveredBytes()).isLessThan(4_194_304L);
		assertThat(httpClient.cancelled()).isTrue();
	}

	@Test
	@DisplayName("HTTP 429는 본문이 성공처럼 보여도 quota 초과로, 그 밖의 비 2xx는 원천 불가로 닫는다")
	void httpStatusFailuresKeepProviderCausesEvenWithSuccessfulLookingBody() {
		byte[] successBody = ONE_ARRIVAL_PAYLOAD.getBytes(StandardCharsets.UTF_8);

		assertThatThrownBy(() -> provider(new ChunkedBodyHttpClient(429, successBody)).arrivals(EULJIRO_3GA_LINE_3_ARRIVALS))
			.isInstanceOf(RealtimeProviderException.class)
			.hasMessage("PROVIDER_QUOTA_EXCEEDED");
		assertThatThrownBy(() -> provider(new ChunkedBodyHttpClient(503, successBody)).arrivals(EULJIRO_3GA_LINE_3_ARRIVALS))
			.isInstanceOf(RealtimeProviderException.class)
			.hasMessage("PROVIDER_UNAVAILABLE");
	}

	private static TopisRealtimeProvider provider(HttpClient httpClient) {
		return new TopisRealtimeProvider("backend-key", JSON, httpClient);
	}

	/**
	 * 역 단위 도착 원천처럼 요청 경로의 {startIndex}/{endIndex} 범위(양 끝 포함) 행만 돌려주는 HTTP stub.
	 */
	private static HttpClient stationRowsClient(List<String> stationRows, List<URI> requested) throws Exception {
		HttpClient httpClient = mock(HttpClient.class);
		when(httpClient.<InputStream>send(any(HttpRequest.class), any())).thenAnswer(invocation -> {
			HttpRequest request = invocation.getArgument(0);
			requested.add(request.uri());
			String[] segments = request.uri().getRawPath().split("/");
			int startIndex = Integer.parseInt(segments[segments.length - 3]);
			int endIndex = Integer.parseInt(segments[segments.length - 2]);
			List<String> served = stationRows.subList(
				Math.min(startIndex, stationRows.size()),
				Math.min(endIndex + 1, stationRows.size())
			);
			String body = "{\"errorMessage\":{\"status\":200,\"code\":\"INFO-000\",\"message\":\"정상 처리되었습니다.\"},"
				+ "\"realtimeArrivalList\":[" + String.join(",", served) + "]}";
			InputStream stream = new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8));
			return new StubResponse<>(request, 200, stream);
		});
		return httpClient;
	}

	private static String euljiro3gaRow(String subwayId, String trainNo, String direction, String etaSeconds) {
		return ("{\"subwayId\":\"%s\",\"statnNm\":\"을지로3가\",\"trainLineNm\":\"%s방면\",\"updnLine\":\"%s\","
			+ "\"btrainNo\":\"%s\",\"barvlDt\":\"%s\",\"arvlMsg2\":\"곧 도착\",\"recptnDt\":\"2026-09-29 08:00:00\"}")
			.formatted(subwayId, direction, direction, trainNo, etaSeconds);
	}

	private static RealtimeMappingPort euljiro3gaLine3MappingPort() {
		RealtimeMapping mapping = new RealtimeMapping(
			"seoul-topis",
			"station-euljiro-3ga",
			"seoul-3",
			"1003",
			"1003000322",
			"을지로3가",
			"3호선",
			true,
			true,
			"OFFICIAL",
			1L
		);
		return new RealtimeMappingPort() {
			@Override
			public Optional<RealtimeMapping> findArrivalMapping(String providerId, RealtimeQuery query) {
				return Optional.of(mapping).filter(candidate -> candidate.stationId().equals(query.stationId()));
			}

			@Override
			public Optional<RealtimeMapping> findTrainPositionMapping(String providerId, RealtimeQuery query) {
				return Optional.of(mapping);
			}

			@Override
			public Optional<RealtimeTripMapping> findTripMapping(
				String providerId,
				String lineId,
				String providerLineId,
				String rawDirection,
				String rawDestination,
				String rawServicePattern
			) {
				return Optional.empty();
			}
		};
	}

	private static byte[] paddedBody(String json, int totalBytes) {
		byte[] payload = json.getBytes(StandardCharsets.UTF_8);
		assertThat(payload.length).isLessThan(totalBytes);
		byte[] body = new byte[totalBytes];
		Arrays.fill(body, (byte) ' ');
		System.arraycopy(payload, 0, body, 0, payload.length);
		return body;
	}

	/**
	 * 헤더 뒤 본문이 멈춘 원천을 흉내 낸다. 첫 read는 close되거나 stall 시간이 지날 때까지 막히고,
	 * close로 풀리면 실제 HttpResponse 본문 스트림처럼 IOException으로 끝난다. stall 뒤에는 본문을 그대로 준다.
	 */
	private static final class StalledBody extends InputStream {
		private final ByteArrayInputStream delegate;
		private final Duration stall;
		private final CountDownLatch released = new CountDownLatch(1);
		private final AtomicBoolean closed = new AtomicBoolean();

		private StalledBody(byte[] body, Duration stall) {
			this.delegate = new ByteArrayInputStream(body);
			this.stall = stall;
		}

		boolean closed() {
			return closed.get();
		}

		@Override
		public int read() throws IOException {
			byte[] one = new byte[1];
			int read = read(one, 0, 1);
			return read == -1 ? -1 : one[0] & 0xff;
		}

		@Override
		public int read(byte[] buffer, int offset, int length) throws IOException {
			try {
				released.await(stall.toMillis(), TimeUnit.MILLISECONDS);
			} catch (InterruptedException exception) {
				Thread.currentThread().interrupt();
				throw new InterruptedIOException("stalled body read interrupted");
			}
			if (closed.get()) {
				throw new IOException("closed");
			}
			return delegate.read(buffer, offset, length);
		}

		@Override
		public void close() {
			closed.set(true);
			released.countDown();
		}
	}

	/**
	 * provider logger에 WARN 이상을 모으는 appender를 잠시 붙인다.
	 */
	private static final class CapturedProviderLog implements AutoCloseable {
		private final LoggerContext loggerContext;
		private final Configuration configuration;
		private final String loggerName;
		private final LoggerConfig previousExactConfig;
		private final AbstractAppender appender;

		private CapturedProviderLog(List<LogEvent> events) {
			this.loggerContext = (LoggerContext) LogManager.getContext(false);
			this.configuration = loggerContext.getConfiguration();
			this.loggerName = TopisRealtimeProvider.class.getName();
			this.previousExactConfig = configuration.getLoggers().get(loggerName);
			this.appender = new AbstractAppender(
				"topis-realtime-provider-test",
				null,
				PatternLayout.createDefaultLayout(),
				false,
				Property.EMPTY_ARRAY
			) {
				@Override
				public void append(LogEvent event) {
					events.add(event.toImmutable());
				}
			};
			appender.start();
			configuration.addAppender(appender);
			LoggerConfig loggerConfig = new LoggerConfig(loggerName, Level.WARN, false);
			loggerConfig.addAppender(appender, Level.WARN, null);
			configuration.addLogger(loggerName, loggerConfig);
			loggerContext.updateLoggers();
		}

		static CapturedProviderLog capture(List<LogEvent> events) {
			return new CapturedProviderLog(events);
		}

		@Override
		public void close() {
			configuration.removeLogger(loggerName);
			if (previousExactConfig != null) {
				configuration.addLogger(loggerName, previousExactConfig);
			}
			loggerContext.updateLoggers();
			appender.stop();
		}
	}

	/**
	 * 실제 HttpClient처럼 호출자가 넘긴 BodyHandler를 구동하고, 구독자 demand만큼만 64 KiB 조각을 흘려 준다.
	 * 구독자가 얼마나 받아 갔는지와 구독 취소 여부를 기록한다.
	 */
	private static final class ChunkedBodyHttpClient extends HttpClient {
		private static final int CHUNK_BYTES = 65_536;

		private final int status;
		private final byte[] body;
		private final AtomicLong deliveredBytes = new AtomicLong();
		private final AtomicBoolean cancelled = new AtomicBoolean();

		private ChunkedBodyHttpClient(int status, byte[] body) {
			this.status = status;
			this.body = body;
		}

		static ChunkedBodyHttpClient ok(String json) {
			return new ChunkedBodyHttpClient(200, json.getBytes(StandardCharsets.UTF_8));
		}

		long deliveredBytes() {
			return deliveredBytes.get();
		}

		boolean cancelled() {
			return cancelled.get();
		}

		@Override
		public <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> responseBodyHandler)
			throws IOException, InterruptedException {
			HttpResponse.BodySubscriber<T> subscriber = responseBodyHandler.apply(new StubResponseInfo(status));
			subscriber.onSubscribe(new ChunkSubscription(subscriber));
			try {
				T responseBody = subscriber.getBody().toCompletableFuture().get();
				return new StubResponse<>(request, status, responseBody);
			} catch (ExecutionException exception) {
				throw new IOException("stub body subscriber failed", exception.getCause());
			}
		}

		@Override
		public <T> CompletableFuture<HttpResponse<T>> sendAsync(
			HttpRequest request,
			HttpResponse.BodyHandler<T> responseBodyHandler
		) {
			return CompletableFuture.failedFuture(new UnsupportedOperationException("sendAsync is not stubbed"));
		}

		@Override
		public <T> CompletableFuture<HttpResponse<T>> sendAsync(
			HttpRequest request,
			HttpResponse.BodyHandler<T> responseBodyHandler,
			HttpResponse.PushPromiseHandler<T> pushPromiseHandler
		) {
			return CompletableFuture.failedFuture(new UnsupportedOperationException("sendAsync is not stubbed"));
		}

		@Override
		public Optional<java.net.CookieHandler> cookieHandler() {
			return Optional.empty();
		}

		@Override
		public Optional<Duration> connectTimeout() {
			return Optional.empty();
		}

		@Override
		public Redirect followRedirects() {
			return Redirect.NEVER;
		}

		@Override
		public Optional<java.net.ProxySelector> proxy() {
			return Optional.empty();
		}

		@Override
		public SSLContext sslContext() {
			return null;
		}

		@Override
		public SSLParameters sslParameters() {
			return null;
		}

		@Override
		public Optional<java.net.Authenticator> authenticator() {
			return Optional.empty();
		}

		@Override
		public Version version() {
			return Version.HTTP_1_1;
		}

		@Override
		public Optional<Executor> executor() {
			return Optional.empty();
		}

		private final class ChunkSubscription implements Flow.Subscription {
			private final Flow.Subscriber<? super List<ByteBuffer>> subscriber;
			private long demand;
			private int offset;
			private boolean delivering;
			private boolean completed;

			private ChunkSubscription(Flow.Subscriber<? super List<ByteBuffer>> subscriber) {
				this.subscriber = subscriber;
			}

			@Override
			public void request(long n) {
				demand = Long.MAX_VALUE - demand < n ? Long.MAX_VALUE : demand + n;
				if (delivering) {
					return;
				}
				delivering = true;
				try {
					while (demand > 0 && !completed && !cancelled.get()) {
						int length = Math.min(CHUNK_BYTES, body.length - offset);
						ByteBuffer chunk = ByteBuffer.wrap(body, offset, length).slice();
						offset += length;
						demand--;
						deliveredBytes.addAndGet(length);
						subscriber.onNext(List.of(chunk));
						if (offset == body.length) {
							completed = true;
							subscriber.onComplete();
						}
					}
				} finally {
					delivering = false;
				}
			}

			@Override
			public void cancel() {
				cancelled.set(true);
			}
		}
	}

	private record StubResponseInfo(int statusCode) implements HttpResponse.ResponseInfo {
		@Override
		public HttpHeaders headers() {
			return HttpHeaders.of(Map.of(), (name, value) -> true);
		}

		@Override
		public HttpClient.Version version() {
			return HttpClient.Version.HTTP_1_1;
		}
	}

	private record StubResponse<T>(HttpRequest request, int statusCode, T body) implements HttpResponse<T> {
		@Override
		public Optional<HttpResponse<T>> previousResponse() {
			return Optional.empty();
		}

		@Override
		public HttpHeaders headers() {
			return HttpHeaders.of(Map.of(), (name, value) -> true);
		}

		@Override
		public Optional<SSLSession> sslSession() {
			return Optional.empty();
		}

		@Override
		public URI uri() {
			return request.uri();
		}

		@Override
		public HttpClient.Version version() {
			return HttpClient.Version.HTTP_1_1;
		}
	}
}
