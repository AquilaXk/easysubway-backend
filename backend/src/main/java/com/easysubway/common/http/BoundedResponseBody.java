package com.easysubway.common.http;

import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/**
 * 외부 HTTP 응답 본문을 바이트 상한과 수신 시간 제한 안에서 읽는다.
 *
 * <p>{@link java.net.http.HttpRequest#timeout(Duration)}은 응답 헤더까지만 기다리므로, {@code ofInputStream}으로
 * 받은 본문은 여기서 따로 제한한다.
 * <ul>
 *   <li>상한을 1바이트라도 넘으면 앞부분을 잘라 돌려주지 않고 {@code oversized} 예외를 던진다. 최대 상한+1바이트까지만 읽는다.</li>
 *   <li>시간 제한이 지나면 본문 스트림을 닫아 막힌 read를 끊고 {@link HttpTimeoutException}을 던진다.
 *       남은 시간이 없으면 읽지 않고 바로 던진다.</li>
 *   <li>그 밖의 수신 오류는 원래 {@link IOException} 그대로 던진다.</li>
 * </ul>
 */
public final class BoundedResponseBody {

	private BoundedResponseBody() {
	}

	public static byte[] read(
		InputStream body,
		int maxBytes,
		Duration timeout,
		Supplier<? extends RuntimeException> oversized
	) throws IOException {
		if (timeout.isZero() || timeout.isNegative()) {
			throw new HttpTimeoutException("response body read budget exhausted");
		}
		AtomicBoolean timedOut = new AtomicBoolean();
		byte[] bytes;
		// shutdownNow로 예약 작업을 먼저 버리므로 try-with-resources의 close()는 기다리지 않고 바로 끝난다.
		try (ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(task -> {
			Thread thread = new Thread(task, "bounded-response-body-timeout");
			thread.setDaemon(true);
			return thread;
		})) {
			try {
				ScheduledFuture<?> closeOnTimeout = timer.schedule(() -> {
					timedOut.set(true);
					closeStalledBody(body);
				}, timeout.toNanos(), TimeUnit.NANOSECONDS);
				try {
					bytes = body.readNBytes(maxBytes + 1);
				} finally {
					closeOnTimeout.cancel(false);
				}
			} catch (IOException exception) {
				if (!timedOut.get()) {
					throw exception;
				}
				throw timedOut(exception);
			} finally {
				timer.shutdownNow();
			}
		}
		if (timedOut.get()) {
			throw timedOut(null);
		}
		if (bytes.length > maxBytes) {
			throw oversized.get();
		}
		return bytes;
	}

	private static void closeStalledBody(InputStream body) {
		try {
			body.close();
		} catch (IOException closeFailure) {
			// 막힌 read를 끊는 것이 목적이다. 결과는 read 쪽이 timedOut 플래그로 HttpTimeoutException을 던져 알린다.
		}
	}

	private static HttpTimeoutException timedOut(IOException cause) {
		HttpTimeoutException timeout = new HttpTimeoutException("response body read timed out");
		if (cause != null) {
			timeout.initCause(cause);
		}
		return timeout;
	}
}
