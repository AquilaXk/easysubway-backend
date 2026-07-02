package com.easysubway.route.adapter.out.realtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.easysubway.realtime.application.RealtimeArrivalResult;
import com.easysubway.realtime.application.RealtimeGatewayService;
import com.easysubway.realtime.application.RealtimeQuery;
import com.easysubway.realtime.domain.RealtimeArrival;
import com.easysubway.route.application.port.out.RealtimeArrivalResolver;
import com.easysubway.route.domain.ArrivalCandidate;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class RealtimeGatewayArrivalResolverTest {

	@Test
	@DisplayName("gateway가 조정한 ETA는 provider timestamp 기준 expectedArrivalAt으로 변환한다")
	void adjustedEtaUsesProviderTimestampAsBase() {
		RealtimeGatewayService gatewayService = org.mockito.Mockito.mock(RealtimeGatewayService.class);
		when(gatewayService.arrivals(any(RealtimeQuery.class))).thenReturn(RealtimeArrivalResult.fresh(
			"2026-06-26T08:00:00Z",
			List.of(new RealtimeArrival(
				"line-4",
				"상록수",
				"사당",
				"상행",
				"T1001",
				150,
				"3분 후",
				"전역 출발",
				"2026-06-26T07:59:30Z",
				"급행"
			))
		));
		RealtimeGatewayArrivalResolver resolver = new RealtimeGatewayArrivalResolver(gatewayService);

		RealtimeArrivalResolver.Resolution resolution = resolver.resolve(new RealtimeArrivalResolver.Query(
			"station-sangnoksu",
			"line-4",
			"1004",
			"상록수",
			"4호선",
			"상행",
			Instant.parse("2026-06-26T08:00:00Z")
		));

		ArrivalCandidate candidate = resolution.candidates().getFirst();
		assertThat(candidate.providerReceivedAt()).isEqualTo(Instant.parse("2026-06-26T07:59:30Z"));
		assertThat(candidate.expectedArrivalAt()).isEqualTo(Instant.parse("2026-06-26T08:02:00Z"));
		assertThat(candidate.servicePattern()).isEqualTo("급행");
	}

	@Test
	@DisplayName("provider timestamp가 없는 realtime 도착 후보는 anchor 없는 ETA로 쓰지 않는다")
	void realtimeCandidateRequiresProviderTimestampAnchor() {
		RealtimeGatewayService gatewayService = org.mockito.Mockito.mock(RealtimeGatewayService.class);
		when(gatewayService.arrivals(any(RealtimeQuery.class))).thenReturn(RealtimeArrivalResult.fresh(
			"2026-06-26T08:00:00Z",
			List.of(new RealtimeArrival(
				"line-4",
				"상록수",
				"사당",
				"상행",
				"T1001",
				150,
				"3분 후",
				"전역 출발",
				null
			))
		));
		RealtimeGatewayArrivalResolver resolver = new RealtimeGatewayArrivalResolver(gatewayService);

		RealtimeArrivalResolver.Resolution resolution = resolver.resolve(new RealtimeArrivalResolver.Query(
			"station-sangnoksu",
			"line-4",
			"1004",
			"상록수",
			"4호선",
			"상행",
			Instant.parse("2026-06-26T08:00:00Z")
		));

		assertThat(resolution.candidates()).isEmpty();
	}
}
