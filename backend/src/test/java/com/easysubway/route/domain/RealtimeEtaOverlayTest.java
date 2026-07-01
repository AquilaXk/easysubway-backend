package com.easysubway.route.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("실시간 ETA overlay")
class RealtimeEtaOverlayTest {

	private static final Instant READY_AT = Instant.parse("2026-07-01T00:00:00Z");
	private static final int PLANNED_WAIT_SECONDS = 300;

	private final RealtimeEtaOverlay overlay = new RealtimeEtaOverlay();

	@Test
	@DisplayName("fresh realtime 후보는 첫 승차 대기시간과 provider 증거를 반영한다")
	void freshRealtimeCandidateUpdatesWaitTime() {
		ArrivalCandidate candidate = new ArrivalCandidate(
			"train-401",
			"seoul-4",
			"당고개 방면",
			"당고개",
			120,
			READY_AT.plusSeconds(120),
			Instant.parse("2026-06-30T23:59:30Z"),
			ArrivalFreshness.FRESH_REALTIME,
			EtaConfidence.HIGH
		);

		RealtimeEtaOverlay.Result result = overlay.overlay(
			READY_AT,
			PLANNED_WAIT_SECONDS,
			"당고개 방면",
			ArrivalFreshness.FRESH_REALTIME,
			null,
			List.of(candidate)
		);

		assertThat(result.etaSource()).isEqualTo(EtaSource.REALTIME);
		assertThat(result.confidence()).isEqualTo(EtaConfidence.HIGH);
		assertThat(result.plannedWaitSeconds()).isEqualTo(PLANNED_WAIT_SECONDS);
		assertThat(result.waitSeconds()).isEqualTo(120);
		assertThat(result.trainNo()).isEqualTo("train-401");
		assertThat(result.providerEvidence()).isEqualTo("providerReceivedAt=2026-06-30T23:59:30Z");
		assertThat(result.warningCodes()).isEmpty();
	}

	@Test
	@DisplayName("stale realtime은 경고만 남기고 planned timetable을 사용한다")
	void staleRealtimeUsesPlannedWait() {
		RealtimeEtaOverlay.Result result = overlay.overlay(
			READY_AT,
			PLANNED_WAIT_SECONDS,
			"당고개 방면",
			ArrivalFreshness.STALE_REALTIME,
			"STALE_CACHE",
			List.of()
		);

		assertThat(result.etaSource()).isEqualTo(EtaSource.PLANNED);
		assertThat(result.confidence()).isEqualTo(EtaConfidence.MEDIUM);
		assertThat(result.waitSeconds()).isEqualTo(PLANNED_WAIT_SECONDS);
		assertThat(result.warningCodes()).containsExactly("STALE_REALTIME");
	}

	@Test
	@DisplayName("unavailable provider는 route search를 실패시키지 않고 fallback source와 오류 코드를 남긴다")
	void unavailableProviderUsesFallbackWait() {
		RealtimeEtaOverlay.Result result = overlay.overlay(
			READY_AT,
			PLANNED_WAIT_SECONDS,
			"당고개 방면",
			ArrivalFreshness.UNAVAILABLE,
			"PROVIDER_QUOTA_EXCEEDED",
			List.of()
		);

		assertThat(result.etaSource()).isEqualTo(EtaSource.FALLBACK);
		assertThat(result.confidence()).isEqualTo(EtaConfidence.LOW);
		assertThat(result.waitSeconds()).isEqualTo(PLANNED_WAIT_SECONDS);
		assertThat(result.warningCodes())
			.containsExactly("REALTIME_UNAVAILABLE_PLANNED_USED", "PROVIDER_QUOTA_EXCEEDED");
	}

	@Test
	@DisplayName("empty provider result는 planned fallback과 provider health 코드를 남긴다")
	void emptyProviderResultUsesFallbackWait() {
		RealtimeEtaOverlay.Result result = overlay.overlay(
			READY_AT,
			PLANNED_WAIT_SECONDS,
			"당고개 방면",
			ArrivalFreshness.EMPTY_PROVIDER_RESULT,
			"EMPTY_PROVIDER_RESULT",
			List.of()
		);

		assertThat(result.etaSource()).isEqualTo(EtaSource.FALLBACK);
		assertThat(result.waitSeconds()).isEqualTo(PLANNED_WAIT_SECONDS);
		assertThat(result.warningCodes()).containsExactly("EMPTY_PROVIDER_RESULT");
	}

	@Test
	@DisplayName("unsupported realtime은 planned timetable만 사용한다")
	void unsupportedRealtimeUsesPlannedWait() {
		RealtimeEtaOverlay.Result result = overlay.overlay(
			READY_AT,
			PLANNED_WAIT_SECONDS,
			"당고개 방면",
			ArrivalFreshness.UNSUPPORTED,
			"UNSUPPORTED_REGION",
			List.of()
		);

		assertThat(result.etaSource()).isEqualTo(EtaSource.PLANNED);
		assertThat(result.waitSeconds()).isEqualTo(PLANNED_WAIT_SECONDS);
		assertThat(result.warningCodes()).containsExactly("UNSUPPORTED_REALTIME");
	}

	@Test
	@DisplayName("방향이 맞지 않는 fresh 후보는 ETA 개선에 사용하지 않는다")
	void freshRealtimeCandidateRequiresMatchingDirection() {
		ArrivalCandidate wrongDirection = new ArrivalCandidate(
			"train-402",
			"seoul-4",
			"오이도 방면",
			"오이도",
			90,
			READY_AT.plusSeconds(90),
			READY_AT.minusSeconds(20),
			ArrivalFreshness.FRESH_REALTIME,
			EtaConfidence.HIGH
		);

		RealtimeEtaOverlay.Result result = overlay.overlay(
			READY_AT,
			PLANNED_WAIT_SECONDS,
			"당고개 방면",
			ArrivalFreshness.FRESH_REALTIME,
			null,
			List.of(wrongDirection)
		);

		assertThat(result.etaSource()).isEqualTo(EtaSource.FALLBACK);
		assertThat(result.waitSeconds()).isEqualTo(PLANNED_WAIT_SECONDS);
		assertThat(result.warningCodes()).containsExactly("NO_USABLE_REALTIME_CANDIDATE");
	}
}
