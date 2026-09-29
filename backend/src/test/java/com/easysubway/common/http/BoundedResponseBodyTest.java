package com.easysubway.common.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("외부 HTTP 응답 본문 상한·시간 제한 읽기")
class BoundedResponseBodyTest {

	private static final Duration GENEROUS = Duration.ofSeconds(5);

	@Test
	@DisplayName("상한과 정확히 같은 크기의 본문은 그대로 돌려준다")
	void returnsBodyExactlyAtLimit() throws IOException {
		byte[] body = "12345678".getBytes(StandardCharsets.UTF_8);

		byte[] read = BoundedResponseBody.read(new CountingStream(body), 8, GENEROUS, Oversized::new);

		assertThat(read).isEqualTo(body);
	}

	@Test
	@DisplayName("상한을 1바이트라도 넘으면 앞부분을 돌려주지 않고 호출자 예외를 던지며, 상한+1바이트 넘게 읽지 않는다")
	void rejectsBodyOverLimitWithoutReadingToTheEnd() {
		CountingStream body = new CountingStream(new byte[1_048_576]);

		assertThatThrownBy(() -> BoundedResponseBody.read(body, 1_024, GENEROUS, Oversized::new))
			.isInstanceOf(Oversized.class);
		assertThat(body.bytesRead()).isLessThanOrEqualTo(1_025L);
	}

	@Test
	@DisplayName("시간 제한 안에 끝나지 않는 본문은 스트림을 닫아 끊고 HttpTimeoutException으로 알린다")
	void closesAStalledBodyAndReportsTimeout() {
		StalledStream body = new StalledStream(Duration.ofSeconds(6));
		long startedAt = System.nanoTime();

		assertThatThrownBy(() -> BoundedResponseBody.read(body, 1_024, Duration.ofMillis(100), Oversized::new))
			.isInstanceOf(HttpTimeoutException.class);
		assertThat(Duration.ofNanos(System.nanoTime() - startedAt)).isLessThan(Duration.ofSeconds(3));
		assertThat(body.closed()).isTrue();
	}

	@Test
	@DisplayName("남은 시간 예산이 없으면 본문을 읽지 않고 곧바로 HttpTimeoutException으로 알린다")
	void exhaustedBudgetIsTimeoutWithoutReading() {
		for (Duration exhausted : new Duration[] {Duration.ZERO, Duration.ofMillis(-1)}) {
			CountingStream body = new CountingStream("{}".getBytes(StandardCharsets.UTF_8));

			assertThatThrownBy(() -> BoundedResponseBody.read(body, 1_024, exhausted, Oversized::new))
				.as(exhausted.toString())
				.isInstanceOf(HttpTimeoutException.class);
			assertThat(body.bytesRead()).as(exhausted.toString()).isZero();
		}
	}

	@Test
	@DisplayName("시간 제한 전에 난 수신 오류는 timeout으로 바꾸지 않고 원래 IOException을 그대로 던진다")
	void transportFailureBeforeTimeoutIsNotReportedAsTimeout() {
		IOException reset = new IOException("connection reset");
		InputStream body = new InputStream() {
			@Override
			public int read() throws IOException {
				throw reset;
			}
		};

		assertThatThrownBy(() -> BoundedResponseBody.read(body, 1_024, GENEROUS, Oversized::new))
			.isSameAs(reset);
	}

	@Test
	@DisplayName("(1) 200회 연속 읽기를 수행한 뒤 bounded-response-body-timeout 데몬 스레드는 1개 이하로 유지된다")
	void maintainsSingleTimeoutThreadUnderConsecutiveReads() throws IOException {
		byte[] payload = "test-payload".getBytes(StandardCharsets.UTF_8);
		for (int i = 0; i < 200; i++) {
			byte[] read = BoundedResponseBody.read(new ByteArrayInputStream(payload), 1024, GENEROUS, Oversized::new);
			assertThat(read).isEqualTo(payload);
		}

		long aliveTimeoutThreads = Thread.getAllStackTraces().keySet().stream()
			.filter(t -> "bounded-response-body-timeout".equals(t.getName()) && t.isAlive())
			.count();
		assertThat(aliveTimeoutThreads).isLessThanOrEqualTo(1L);
	}

	@Test
	@DisplayName("(2) 호출이 끝난 뒤 공유 스케줄러 큐에 남은 작업은 0개이다")
	void leavesZeroPendingTasksInSharedSchedulerQueueAfterRead() throws IOException {
		byte[] payload = "queue-test".getBytes(StandardCharsets.UTF_8);
		for (int i = 0; i < 10; i++) {
			BoundedResponseBody.read(new ByteArrayInputStream(payload), 1024, GENEROUS, Oversized::new);
		}

		assertThat(scheduledQueueSize()).isZero();
	}

	@Test
	@DisplayName("(3) 여러 스레드가 동시에 읽을 때 한 호출의 시간 초과가 다른 호출의 스트림을 닫지 않는다")
	void timeoutInOneThreadDoesNotAffectConcurrentReads() throws Exception {
		CountDownLatch timedOutLatch = new CountDownLatch(1);
		AtomicBoolean threadBSuccess = new AtomicBoolean(false);
		AtomicReference<Throwable> threadBError = new AtomicReference<>();

		StalledStream stalledA = new StalledStream(Duration.ofSeconds(5));

		byte[] payloadB = "concurrent-read-payload".getBytes(StandardCharsets.UTF_8);
		InputStream delayedStreamB = new InputStream() {
			private final ByteArrayInputStream delegate = new ByteArrayInputStream(payloadB);

			@Override
			public int read() throws IOException {
				try {
					timedOutLatch.await(3, TimeUnit.SECONDS);
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
					throw new InterruptedIOException("interrupted");
				}
				return delegate.read();
			}

			@Override
			public int read(byte[] b, int off, int len) throws IOException {
				try {
					timedOutLatch.await(3, TimeUnit.SECONDS);
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
					throw new InterruptedIOException("interrupted");
				}
				return delegate.read(b, off, len);
			}
		};

		Thread threadA = new Thread(() -> {
			try {
				BoundedResponseBody.read(stalledA, 1024, Duration.ofMillis(50), Oversized::new);
			} catch (HttpTimeoutException expected) {
				timedOutLatch.countDown();
			} catch (IOException unexpected) {
				// ignore
			}
		});

		Thread threadB = new Thread(() -> {
			try {
				byte[] read = BoundedResponseBody.read(delayedStreamB, 1024, Duration.ofSeconds(3), Oversized::new);
				if (java.util.Arrays.equals(read, payloadB)) {
					threadBSuccess.set(true);
				}
			} catch (Throwable t) {
				threadBError.set(t);
			}
		});

		threadA.start();
		threadB.start();

		threadA.join(5000);
		threadB.join(5000);

		assertThat(stalledA.closed()).isTrue();
		assertThat(threadBError.get()).isNull();
		assertThat(threadBSuccess.get()).isTrue();
	}

	@Test
	@DisplayName("(4) 막힌 스트림은 지정된 시간 제한 안에 HttpTimeoutException으로 끊어진다")
	void blockedStreamThrowsHttpTimeoutExceptionWithinBudget() {
		StalledStream body = new StalledStream(Duration.ofSeconds(10));
		long startedAt = System.nanoTime();

		assertThatThrownBy(() -> BoundedResponseBody.read(body, 1_024, Duration.ofMillis(80), Oversized::new))
			.isInstanceOf(HttpTimeoutException.class);
		assertThat(Duration.ofNanos(System.nanoTime() - startedAt)).isLessThan(Duration.ofSeconds(2));
		assertThat(body.closed()).isTrue();
	}

	private static int scheduledQueueSize() {
		try {
			var field = BoundedResponseBody.class.getDeclaredField("TIMER");
			field.setAccessible(true);
			var executor = (java.util.concurrent.ScheduledThreadPoolExecutor) field.get(null);
			return executor.getQueue().size();
		} catch (ReflectiveOperationException exception) {
			throw new AssertionError("Shared scheduler TIMER not found on BoundedResponseBody", exception);
		}
	}

	private static final class Oversized extends RuntimeException {
		private Oversized() {
			super("oversized");
		}
	}

	private static final class CountingStream extends InputStream {
		private final ByteArrayInputStream delegate;
		private final AtomicLong bytesRead = new AtomicLong();

		private CountingStream(byte[] body) {
			this.delegate = new ByteArrayInputStream(body);
		}

		long bytesRead() {
			return bytesRead.get();
		}

		@Override
		public int read() {
			int value = delegate.read();
			if (value != -1) {
				bytesRead.incrementAndGet();
			}
			return value;
		}

		@Override
		public int read(byte[] buffer, int offset, int length) {
			int read = delegate.read(buffer, offset, length);
			if (read > 0) {
				bytesRead.addAndGet(read);
			}
			return read;
		}
	}

	/**
	 * close되거나 stall 시간이 지날 때까지 read가 막히는 본문. close로 풀리면 IOException으로 끝난다.
	 */
	private static final class StalledStream extends InputStream {
		private final Duration stall;
		private final CountDownLatch released = new CountDownLatch(1);
		private final AtomicBoolean closed = new AtomicBoolean();

		private StalledStream(Duration stall) {
			this.stall = stall;
		}

		boolean closed() {
			return closed.get();
		}

		@Override
		public int read() throws IOException {
			try {
				released.await(stall.toMillis(), TimeUnit.MILLISECONDS);
			} catch (InterruptedException exception) {
				Thread.currentThread().interrupt();
				throw new InterruptedIOException("stalled read interrupted");
			}
			if (closed.get()) {
				throw new IOException("closed");
			}
			return -1;
		}

		@Override
		public void close() {
			closed.set(true);
			released.countDown();
		}
	}
}
