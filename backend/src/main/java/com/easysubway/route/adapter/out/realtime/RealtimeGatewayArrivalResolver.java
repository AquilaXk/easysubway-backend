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
			candidates(result, status)
		);
	}

	private List<ArrivalCandidate> candidates(
		RealtimeArrivalResult result,
		ArrivalFreshness status
	) {
		return result.arrivals()
			.stream()
			.filter(arrival -> arrival.etaSeconds() != null)
			.filter(arrival -> arrival.etaSeconds() >= 0)
			.map(arrival -> candidate(arrival, status))
			.filter(Objects::nonNull)
			.toList();
	}

	private ArrivalCandidate candidate(
		RealtimeArrival arrival,
		ArrivalFreshness status
	) {
		Instant providerReceivedAt = parseInstant(arrival.providerReceivedAt());
		if (providerReceivedAt == null) {
			return null;
		}
		Instant expectedArrivalAt = providerReceivedAt.plusSeconds(arrival.etaSeconds());
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
