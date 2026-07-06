package com.easysubway.route.adapter.out.realtime;

import com.easysubway.realtime.application.RealtimeArrivalResult;
import com.easysubway.realtime.application.RealtimeGatewayService;
import com.easysubway.realtime.application.RealtimeQuery;
import com.easysubway.realtime.domain.RealtimeArrival;
import com.easysubway.realtime.domain.RealtimeStatus;
import com.easysubway.route.application.port.out.RealtimeArrivalResolver;
import com.easysubway.route.domain.ArrivalCandidate;
import com.easysubway.route.domain.ArrivalFreshness;
import com.easysubway.route.domain.EtaConfidence;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Component;

@Component
class RealtimeGatewayArrivalResolver implements RealtimeArrivalResolver {

	private final RealtimeGatewayService realtimeGatewayService;

	RealtimeGatewayArrivalResolver(RealtimeGatewayService realtimeGatewayService) {
		this.realtimeGatewayService = realtimeGatewayService;
	}

	@Override
	public Resolution resolve(Query query) {
		RealtimeArrivalResult result = realtimeGatewayService.arrivals(new RealtimeQuery(
			query.stationId(),
			query.lineId(),
			query.providerLineId(),
			query.stationQueryName(),
			query.lineName()
		));
		ArrivalFreshness status = statusOf(result);
		Instant receivedAt = parseInstant(result.receivedAt());
		return new Resolution(
			status,
			result.fallbackCode(),
			providerSnapshotId(result),
			receivedAt,
			candidates(result, receivedAt, status)
		);
	}

	private List<ArrivalCandidate> candidates(
		RealtimeArrivalResult result,
		Instant receivedAt,
		ArrivalFreshness status
	) {
		if (receivedAt == null) {
			return List.of();
		}
		return result.arrivals()
			.stream()
			.filter(arrival -> arrival.etaSeconds() != null)
			.filter(arrival -> arrival.etaSeconds() >= 0)
			.map(arrival -> candidate(arrival, receivedAt, status))
			.filter(Objects::nonNull)
			.toList();
	}

	private ArrivalCandidate candidate(
		RealtimeArrival arrival,
		Instant receivedAt,
		ArrivalFreshness status
	) {
		Instant providerReceivedAt = parseInstant(arrival.providerReceivedAt());
		if (providerReceivedAt == null) {
			// 북극성 Anchor: provider 증거(timestamp) 없는 REALTIME claim은 쓰지 않는다.
			return null;
		}
		// gateway가 수신지연 보정(eta -= now-recptnDt)한 값의 실제 앵커는 gateway 수신시각(receivedAt)이다.
		// providerReceivedAt(recptnDt)에 재앵커하면 지연이 이중 차감된다. 수학적으로 recptnDt + 원본eta 와 동일.
		Instant expectedArrivalAt = receivedAt.plusSeconds(arrival.etaSeconds());
		return new ArrivalCandidate(
			arrival.trainNo(),
			arrival.lineId(),
			arrival.direction(),
			arrival.destination(),
			arrival.etaSeconds(),
			expectedArrivalAt,
			providerReceivedAt,
			arrival.servicePattern(),
			status,
			confidenceOf(status)
		);
	}

	private ArrivalFreshness statusOf(RealtimeArrivalResult result) {
		if (result.status() == RealtimeStatus.FRESH) {
			return ArrivalFreshness.FRESH_REALTIME;
		}
		if (result.status() == RealtimeStatus.STALE) {
			return ArrivalFreshness.STALE_REALTIME;
		}
		if (result.status() == RealtimeStatus.UNSUPPORTED) {
			return ArrivalFreshness.UNSUPPORTED;
		}
		if (ArrivalFreshness.EMPTY_PROVIDER_RESULT.name().equals(result.fallbackCode())) {
			return ArrivalFreshness.EMPTY_PROVIDER_RESULT;
		}
		return ArrivalFreshness.UNAVAILABLE;
	}

	private EtaConfidence confidenceOf(ArrivalFreshness status) {
		return status == ArrivalFreshness.FRESH_REALTIME ? EtaConfidence.HIGH : EtaConfidence.LOW;
	}

	private String providerSnapshotId(RealtimeArrivalResult result) {
		return result.receivedAt() == null ? null : result.providerId() + ":" + result.receivedAt();
	}

	private Instant parseInstant(String value) {
		if (value == null || value.isBlank()) {
			return null;
		}
		try {
			return Instant.parse(value);
		} catch (DateTimeParseException ignored) {
			return null;
		}
	}
}
