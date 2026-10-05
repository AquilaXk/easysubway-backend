package com.easysubway.datapack.adapter.in.web;

import com.easysubway.datapack.adapter.out.persistence.JdbcDatapackReleaseChannelRepository.ReleaseChannelObservationRow;
import com.easysubway.datapack.application.port.out.DatapackReleaseCatalogPort;
import com.easysubway.datapack.application.port.out.DatapackReleaseCatalogPort.CatalogIdentity;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

/** 배포 채널 화면의 git 원본 발행 관측 행. 관측 행이 없으면 값을 채우지 않는다. */
record ReleaseObservationView(
	String channel,
	String channelLabel,
	State state,
	Long observedSequence,
	String manifestSha256,
	String releaseRequestId,
	String workflowRunUrl,
	String observedAtText,
	Long catalogSequence,
	String message
) {

	enum State { ABSENT, MATCH, MISMATCH, CATALOG_UNAVAILABLE, CATALOG_CHANNEL_DIFFERS, NOT_COMPARED }

	private static final List<String> CHANNELS = List.of("production", "staging", "dev");
	private static final String COMPARED_CHANNEL = ReleaseCatalogObservationReader.PRODUCTION;
	private static final DateTimeFormatter OBSERVED_AT_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
	private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

	public boolean isWarning() {
		return state != State.ABSENT && state != State.MATCH && state != State.NOT_COMPARED;
	}

	/** 공개 목록은 운영 채널만 다루므로 운영 관측 행이 있을 때만 읽고 운영 행만 비교한다. */
	static List<ReleaseObservationView> all(
		List<ReleaseChannelObservationRow> observations,
		Supplier<Optional<CatalogIdentity>> productionCatalog
	) {
		boolean productionObserved = observations.stream().anyMatch(row -> COMPARED_CHANNEL.equals(row.channel()));
		Optional<CatalogIdentity> catalog = productionObserved ? productionCatalog.get() : Optional.empty();
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
				"관측 없음. 이 채널의 발행 완료 알림이 아직 도착하지 않았습니다. 발행 실행 기록을 확인하세요.");
		}
		var row = observation.get();
		if (!COMPARED_CHANNEL.equals(channel)) {
			return withState(label, row, State.NOT_COMPARED, null, "비교 대상 아님 (공개 목록은 운영 채널 기준)");
		}
		if (catalog.isEmpty()) {
			return withState(label, row, State.CATALOG_UNAVAILABLE, null,
				"공개 목록을 읽지 못해 비교하지 못했습니다. 공개 목록 주소와 서명 키 설정, 응답을 확인하세요.");
		}
		var current = catalog.get();
		if (!channel.equals(current.channel())) {
			return withState(label, row, State.CATALOG_CHANNEL_DIFFERS, current.releaseSequence(),
				"공개 목록이 운영이 아닌 " + current.channel() + " 채널 기준이라 비교하지 못했습니다.");
		}
		if (current.releaseSequence() != row.releaseSequence()) {
			return withState(label, row, State.MISMATCH, current.releaseSequence(),
				"공개 목록과 발행 번호가 다릅니다. 관측 " + row.releaseSequence() + " / 공개 목록 "
					+ current.releaseSequence() + ". 발행 실행 기록과 공개 목록 반영 상태를 확인하세요.");
		}
		return withState(label, row, State.MATCH, current.releaseSequence(),
			"공개 목록과 일치 (발행 번호 " + current.releaseSequence() + ")");
	}

	private static ReleaseObservationView withState(
		String label, ReleaseChannelObservationRow row, State state, Long catalogSequence, String message
	) {
		return new ReleaseObservationView(row.channel(), label, state, row.releaseSequence(), row.manifestSha256(),
			row.releaseRequestId(), row.workflowRunUrl(), seoulText(row.observedAt()), catalogSequence, message);
	}

	private static String seoulText(LocalDateTime utc) {
		return OBSERVED_AT_FORMAT.format(utc.atOffset(ZoneOffset.UTC).atZoneSameInstant(SEOUL));
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
