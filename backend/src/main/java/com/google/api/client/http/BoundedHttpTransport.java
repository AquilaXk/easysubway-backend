package com.google.api.client.http;

import java.io.IOException;
import java.time.Duration;
import java.util.Objects;

/**
 * Applies an upper bound when a client library creates requests without an initializer hook.
 */
public final class BoundedHttpTransport extends HttpTransport {

	private final HttpTransport delegate;
	private final int connectTimeoutMillis;
	private final int readTimeoutMillis;

	public BoundedHttpTransport(
		HttpTransport delegate,
		Duration connectTimeout,
		Duration readTimeout
	) {
		this.delegate = Objects.requireNonNull(delegate);
		this.connectTimeoutMillis = positiveMillis(connectTimeout);
		this.readTimeoutMillis = positiveMillis(readTimeout);
	}

	@Override
	public boolean supportsMethod(String method) throws IOException {
		return delegate.supportsMethod(method);
	}

	@Override
	public boolean isMtls() {
		return delegate.isMtls();
	}

	@Override
	protected LowLevelHttpRequest buildRequest(String method, String url) throws IOException {
		return new BoundedLowLevelHttpRequest(
			delegate.buildRequest(method, url),
			connectTimeoutMillis,
			readTimeoutMillis
		);
	}

	@Override
	public void shutdown() throws IOException {
		delegate.shutdown();
	}

	@Override
	public boolean isShutdown() {
		return delegate.isShutdown();
	}

	private static int positiveMillis(Duration duration) {
		long millis = Objects.requireNonNull(duration).toMillis();
		if (millis <= 0 || millis > Integer.MAX_VALUE) {
			throw new IllegalArgumentException("timeout must be between 1ms and Integer.MAX_VALUE ms");
		}
		return (int) millis;
	}

	private static int clamp(int requested, int maximum) {
		return requested <= 0 ? maximum : Math.min(requested, maximum);
	}

	private static final class BoundedLowLevelHttpRequest extends LowLevelHttpRequest {

		private final LowLevelHttpRequest delegate;
		private final int connectTimeoutMillis;
		private final int readTimeoutMillis;

		private BoundedLowLevelHttpRequest(
			LowLevelHttpRequest delegate,
			int connectTimeoutMillis,
			int readTimeoutMillis
		) {
			this.delegate = delegate;
			this.connectTimeoutMillis = connectTimeoutMillis;
			this.readTimeoutMillis = readTimeoutMillis;
		}

		@Override
		public void addHeader(String name, String value) throws IOException {
			delegate.addHeader(name, value);
		}

		@Override
		public void setTimeout(int connectTimeout, int readTimeout) throws IOException {
			delegate.setTimeout(
				clamp(connectTimeout, connectTimeoutMillis),
				clamp(readTimeout, readTimeoutMillis)
			);
		}

		@Override
		public void setWriteTimeout(int writeTimeout) throws IOException {
			delegate.setWriteTimeout(clamp(writeTimeout, readTimeoutMillis));
		}

		@Override
		public LowLevelHttpResponse execute() throws IOException {
			delegate.setContentLength(getContentLength());
			delegate.setContentEncoding(getContentEncoding());
			delegate.setContentType(getContentType());
			delegate.setStreamingContent(getStreamingContent());
			return delegate.execute();
		}
	}
}
