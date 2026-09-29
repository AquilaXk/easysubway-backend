package com.easysubway.realtime.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import com.easysubway.realtime.domain.RealtimeArrival;
import com.easysubway.realtime.domain.RealtimeTrainPosition;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSession;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

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
	@DisplayName("최상위 오류 envelope(INFO-100 인증키 오류)는 도착 없음이 아니라 원천 불가로 닫는다")
	void topLevelErrorEnvelopeIsProviderUnavailable() {
		ChunkedBodyHttpClient httpClient = ChunkedBodyHttpClient.ok("""
			{"status": 500, "code": "INFO-100", "message": "인증키가 유효하지 않습니다.",
			 "link": "", "developerMessage": "", "total": 0}
			""");

		assertThatThrownBy(() -> provider(httpClient).arrivals(EULJIRO_3GA_LINE_3_ARRIVALS))
			.isInstanceOf(RealtimeProviderException.class)
			.hasMessage("PROVIDER_UNAVAILABLE");
		assertThatThrownBy(() -> provider(httpClient).trainPositions(LINE_3_POSITIONS))
			.isInstanceOf(RealtimeProviderException.class)
			.hasMessage("PROVIDER_UNAVAILABLE");
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

	private static byte[] paddedBody(String json, int totalBytes) {
		byte[] payload = json.getBytes(StandardCharsets.UTF_8);
		assertThat(payload.length).isLessThan(totalBytes);
		byte[] body = new byte[totalBytes];
		Arrays.fill(body, (byte) ' ');
		System.arraycopy(payload, 0, body, 0, payload.length);
		return body;
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
