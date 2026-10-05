package com.easysubway.datapack.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.easysubway.datapack.adapter.in.web.ReleaseObservationView.State;
import com.easysubway.datapack.adapter.out.persistence.JdbcDatapackReleaseChannelRepository.ReleaseChannelObservationRow;
import com.easysubway.datapack.application.port.out.DatapackReleaseCatalogPort;
import com.easysubway.datapack.application.port.out.DatapackReleaseCatalogPort.CatalogIdentity;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("배포 채널 관측 표시 모델")
class ReleaseObservationViewTest {

	private static final String SHA = "a".repeat(64);
	private static final LocalDateTime AT = LocalDateTime.of(2026, 10, 3, 9, 30);

	private static ReleaseChannelObservationRow row(String channel, long sequence) {
		return new ReleaseChannelObservationRow(channel, sequence, SHA, "request-" + sequence,
			"https://github.com/AquilaXk/easysubway/actions/runs/" + sequence, AT);
	}

	private static CatalogIdentity catalog(String channel, long sequence) {
		return new CatalogIdentity(sequence, SHA, channel, "", true, SHA);
	}

	@Test
	@DisplayName("관측 행이 없으면 값을 채우지 않고 관측 없음 상태가 된다")
	void absentObservationHasNoValues() {
		var view = ReleaseObservationView.of("staging", Optional.empty(), Optional.of(catalog("staging", 5)));

		assertThat(view.state()).isEqualTo(State.ABSENT);
		assertThat(view.observedSequence()).isNull();
		assertThat(view.manifestSha256()).isNull();
		assertThat(view.catalogSequence()).isNull();
		assertThat(view.message()).startsWith("관측 없음");
	}

	@Test
	@DisplayName("관측 sequence와 catalog sequence가 같으면 일치")
	void equalSequenceMatches() {
		var view = ReleaseObservationView.of("production", Optional.of(row("production", 126)),
			Optional.of(catalog("production", 126)));

		assertThat(view.state()).isEqualTo(State.MATCH);
		assertThat(view.isWarning()).isFalse();
	}

	@Test
	@DisplayName("관측 sequence가 catalog보다 크거나 작으면 둘 다 경고")
	void differentSequenceWarns() {
		var ahead = ReleaseObservationView.of("production", Optional.of(row("production", 126)),
			Optional.of(catalog("production", 125)));
		var behind = ReleaseObservationView.of("production", Optional.of(row("production", 124)),
			Optional.of(catalog("production", 125)));

		assertThat(ahead.state()).isEqualTo(State.MISMATCH);
		assertThat(ahead.isWarning()).isTrue();
		assertThat(ahead.message()).contains("126").contains("125");
		assertThat(behind.state()).isEqualTo(State.MISMATCH);
		assertThat(behind.message()).contains("124").contains("125");
	}

	@Test
	@DisplayName("catalog를 읽지 못하면 비교 불가를 표시하고 일치로 보이지 않는다")
	void unreadableCatalogIsShown() {
		var view = ReleaseObservationView.of("production", Optional.of(row("production", 126)), Optional.empty());

		assertThat(view.state()).isEqualTo(State.CATALOG_UNAVAILABLE);
		assertThat(view.isWarning()).isTrue();
		assertThat(view.catalogSequence()).isNull();
		assertThat(view.message()).contains("공개 catalog를 읽지 못");
	}

	@Test
	@DisplayName("catalog가 다른 채널 기준이면 같은 sequence여도 일치로 보지 않는다")
	void otherChannelCatalogIsNotMatch() {
		var view = ReleaseObservationView.of("staging", Optional.of(row("staging", 126)),
			Optional.of(catalog("production", 126)));

		assertThat(view.state()).isEqualTo(State.CATALOG_CHANNEL_DIFFERS);
		assertThat(view.isWarning()).isTrue();
		assertThat(view.message()).contains("production");
	}

	@Test
	@DisplayName("서명이 유효하지 않은 catalog 응답은 읽지 못한 것으로 취급한다")
	void invalidSignatureCatalogIsUnreadable() {
		var port = new FixedCatalog(new CatalogIdentity(126, SHA, "production", "", false, SHA));

		assertThat(ReleaseObservationView.readCatalog(port, "production")).isEmpty();
	}

	@Test
	@DisplayName("catalog 어댑터 장애는 빈 값으로 드러나고 예외로 번지지 않는다")
	void adapterFailureIsUnreadable() {
		assertThat(ReleaseObservationView.readCatalog(new FixedCatalog(null), "production")).isEmpty();
		assertThat(ReleaseObservationView.readCatalog(new FixedCatalog(catalog("production", 3)), "production"))
			.map(CatalogIdentity::releaseSequence).contains(3L);
	}

	@Test
	@DisplayName("관측 행이 하나도 없으면 catalog를 호출하지 않고 세 채널 모두 관측 없음")
	void noObservationSkipsCatalogAndListsAllChannels() {
		var port = new FixedCatalog(catalog("production", 1));

		var views = ReleaseObservationView.all(List.of(), port);

		assertThat(views).extracting(ReleaseObservationView::channel).containsExactly("production", "staging", "dev");
		assertThat(views).extracting(ReleaseObservationView::state).containsOnly(State.ABSENT);
		assertThat(port.calls).isZero();
	}

	@Test
	@DisplayName("관측 채널은 production·staging·dev 순서로 한 번의 catalog 조회로 비교한다")
	void allOrdersChannelsAndReadsCatalogOnce() {
		var port = new FixedCatalog(catalog("production", 126));

		var views = ReleaseObservationView.all(List.of(row("dev", 7), row("production", 126)), port);

		assertThat(views).extracting(ReleaseObservationView::channel).containsExactly("production", "staging", "dev");
		assertThat(views).extracting(ReleaseObservationView::state)
			.containsExactly(State.MATCH, State.ABSENT, State.CATALOG_CHANNEL_DIFFERS);
		assertThat(port.calls).isEqualTo(1);
	}

	private static final class FixedCatalog implements DatapackReleaseCatalogPort {
		private final CatalogIdentity identity;
		int calls;

		FixedCatalog(CatalogIdentity identity) {
			this.identity = identity;
		}

		@Override
		public CatalogIdentity fetch(String channel, long releaseSequence) {
			throw new UnsupportedOperationException();
		}

		@Override
		public CatalogIdentity fetchCurrent(String channel) {
			calls++;
			if (identity == null) {
				throw new Unavailable();
			}
			return identity;
		}

		@Override
		public Optional<CatalogIdentity> findByRequest(String channel, String releaseRequestId) {
			throw new UnsupportedOperationException();
		}
	}
}
