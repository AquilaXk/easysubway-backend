package com.easysubway.datapack.application.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.easysubway.datapack.application.port.out.AutomationStatusRepository;
import com.easysubway.datapack.domain.AutomationAssessment.Level;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("자동화 상태 서비스")
class AutomationStatusServiceTest {

	private static final Instant STARTED = Instant.parse("2026-10-10T00:00:00Z");

	private static final class MovingClock extends Clock {
		private final AtomicReference<Instant> now = new AtomicReference<>(STARTED);

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
			return now.get();
		}

		void advance(Duration duration) {
			now.updateAndGet((current) -> current.plus(duration));
		}
	}

	private static final class EmptyRepository implements AutomationStatusRepository {
		@Override
		public SaveResult save(String payloadJson, Instant generatedAt, Instant receivedAt) {
			return SaveResult.ACCEPTED;
		}

		@Override
		public Optional<StoredPayload> findLatest() {
			return Optional.empty();
		}
	}

	@Test
	@DisplayName("서버가 뜬 뒤 snapshot을 한 번도 받지 못한 채 1시간이 지나면 수신 전이 아니라 이상으로 드러난다")
	void neverReceivedBecomesAnAnomalyAfterAnHour() {
		MovingClock clock = new MovingClock();
		AutomationStatusService service = new AutomationStatusService(
			new EmptyRepository(), new AutomationStatusSnapshotParser(), new AutomationStatusAssessor(), clock);

		assertThat(service.read().assessment().level()).isEqualTo(Level.UNKNOWN);
		clock.advance(Duration.ofMinutes(59));
		assertThat(service.read().assessment().level()).isEqualTo(Level.UNKNOWN);
		clock.advance(Duration.ofMinutes(2));
		assertThat(service.read().assessment().level()).isEqualTo(Level.FAILURE);
		assertThat(service.read().assessment().findings()).extracting((finding) -> finding.code()).containsExactly("NOT_RECEIVED_LONG");
	}
}
