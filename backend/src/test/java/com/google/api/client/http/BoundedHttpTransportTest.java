package com.google.api.client.http;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Google OAuth bounded HTTP transport")
class BoundedHttpTransportTest {

	@Test
	@DisplayName("Google Auth 기본 timeout을 운영 상한으로 줄인다")
	void clampsGoogleAuthTimeouts() throws IOException {
		var delegate = new RecordingTransport();
		var transport = new BoundedHttpTransport(
			delegate,
			Duration.ofSeconds(3),
			Duration.ofSeconds(5)
		);

		LowLevelHttpRequest request = transport.buildRequest("POST", "https://oauth2.googleapis.com/token");
		request.setTimeout(20_000, 20_000);
		request.setWriteTimeout(0);

		assertThat(delegate.request.connectTimeout).isEqualTo(3_000);
		assertThat(delegate.request.readTimeout).isEqualTo(5_000);
		assertThat(delegate.request.writeTimeout).isEqualTo(5_000);

		request.setWriteTimeout(20_000);
		assertThat(delegate.request.writeTimeout).isEqualTo(5_000);
	}

	private static final class RecordingTransport extends HttpTransport {

		private final RecordingRequest request = new RecordingRequest();

		@Override
		protected LowLevelHttpRequest buildRequest(String method, String url) {
			return request;
		}
	}

	private static final class RecordingRequest extends LowLevelHttpRequest {

		private int connectTimeout;
		private int readTimeout;
		private int writeTimeout;

		@Override
		public void addHeader(String name, String value) {
		}

		@Override
		public void setTimeout(int connectTimeout, int readTimeout) {
			this.connectTimeout = connectTimeout;
			this.readTimeout = readTimeout;
		}

		@Override
		public void setWriteTimeout(int writeTimeout) {
			this.writeTimeout = writeTimeout;
		}

		@Override
		public LowLevelHttpResponse execute() {
			throw new UnsupportedOperationException("not needed for timeout verification");
		}
	}
}
