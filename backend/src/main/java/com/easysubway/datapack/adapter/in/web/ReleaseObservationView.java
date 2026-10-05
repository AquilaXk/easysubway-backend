package com.easysubway.datapack.adapter.in.web;

import com.easysubway.datapack.adapter.out.persistence.JdbcDatapackReleaseChannelRepository.ReleaseChannelObservationRow;
import com.easysubway.datapack.application.port.out.DatapackReleaseCatalogPort;
import com.easysubway.datapack.application.port.out.DatapackReleaseCatalogPort.CatalogIdentity;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** 배포 채널 화면의 git 원본 발행 관측 행. 관측 행이 없으면 값을 채우지 않는다. */
record ReleaseObservationView(
	String channel,
	String channelLabel,
	State state,
	Long observedSequence,
	String manifestSha256,
	String releaseRequestId,
	String workflowRunUrl,
	LocalDateTime observedAt,
	Long catalogSequence,
	String message
) {

	enum State { ABSENT, MATCH, MISMATCH, CATALOG_UNAVAILABLE, CATALOG_CHANNEL_DIFFERS }

	private static final List<String> CHANNELS = List.of("production", "staging", "dev");

	public boolean isWarning() {
		return state != State.ABSENT && state != State.MATCH;
	}

	static List<ReleaseObservationView> all(
		List<ReleaseChannelObservationRow> observations,
		DatapackReleaseCatalogPort catalogPort
	) {
		Optional<CatalogIdentity> catalog = observations.isEmpty()
			? Optional.empty()
			: readCatalog(catalogPort, observations.get(0).channel());
		var views = new ArrayList<ReleaseObservationView>();
		for (String channel : CHANNELS) {
			views.add(of(channel, observations.stream().filter(row -> row.channel().equals(channel)).findFirst(), catalog));
		}
		return views;
	}

	static Optional<CatalogIdentity> readCatalog(DatapackReleaseCatalogPort catalogPort, String channel) {
		try {
			return Optional.of(catalogPort.fetchCurrent(channel)).filter(CatalogIdentity::signatureValid);
		} catch (DatapackReleaseCatalogPort.Unavailable unavailable) {
			return Optional.empty();
		}
	}

	static ReleaseObservationView of(
		String channel,
		Optional<ReleaseChannelObservationRow> observation,
		Optional<CatalogIdentity> catalog
	) {
		String label = label(channel);
		if (observation.isEmpty()) {
			return new ReleaseObservationView(channel, label, State.ABSENT, null, null, null, null, null, null,
				"관측 없음. 이 채널의 git 원본 발행 콜백이 아직 도착하지 않았습니다. 발행 workflow 실행 기록을 확인하세요.");
		}
		var row = observation.get();
		if (catalog.isEmpty()) {
			return withState(label, row, State.CATALOG_UNAVAILABLE, null,
				"공개 catalog를 읽지 못해 비교하지 못했습니다. catalog 주소·서명 키 설정과 catalog 응답을 확인하세요.");
		}
		var current = catalog.get();
		if (!channel.equals(current.channel())) {
			return withState(label, row, State.CATALOG_CHANNEL_DIFFERS, current.releaseSequence(),
				"공개 catalog는 " + current.channel() + " 채널 기준(sequence " + current.releaseSequence()
					+ ")이라 이 채널과 비교하지 않았습니다.");
		}
		if (current.releaseSequence() != row.releaseSequence()) {
			return withState(label, row, State.MISMATCH, current.releaseSequence(),
				"공개 catalog와 sequence가 다릅니다. 관측 " + row.releaseSequence() + " / catalog "
					+ current.releaseSequence() + ". 발행 workflow와 catalog 반영 상태를 확인하세요.");
		}
		return withState(label, row, State.MATCH, current.releaseSequence(),
			"공개 catalog와 일치 (sequence " + current.releaseSequence() + ")");
	}

	private static ReleaseObservationView withState(
		String label, ReleaseChannelObservationRow row, State state, Long catalogSequence, String message
	) {
		return new ReleaseObservationView(row.channel(), label, state, row.releaseSequence(), row.manifestSha256(),
			row.releaseRequestId(), row.workflowRunUrl(), row.observedAt(), catalogSequence, message);
	}

	private static String label(String channel) {
		return switch (channel) {
			case "production" -> "프로덕션";
			case "staging" -> "스테이징";
			case "dev" -> "개발";
			default -> channel;
		};
	}
}
