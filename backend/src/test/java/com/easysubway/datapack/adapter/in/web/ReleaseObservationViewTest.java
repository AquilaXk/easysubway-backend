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
		assertThat(view.message()).contains("공개 목록을 읽지 못");
	}

	@Test
	@DisplayName("운영이 아닌 채널은 공개 목록이 다뤄지지 않으므로 경고 없이 비교 대상 아님으로 표시한다")
	void nonProductionChannelIsNotCompared() {
		var view = ReleaseObservationView.of("staging", Optional.of(row("staging", 126)),
			Optional.of(catalog("production", 126)));

		assertThat(view.state()).isEqualTo(State.NOT_COMPARED);
		assertThat(view.isWarning()).isFalse();
		assertThat(view.catalogSequence()).isNull();
		assertThat(view.message()).contains("비교 대상 아님").contains("공개 목록은 운영 채널 기준");
	}

	@Test
	@DisplayName("운영 채널인데 공개 목록이 다른 채널 기준이면 일치로 보지 않고 경고한다")
	void productionWithOtherChannelCatalogWarns() {
		var view = ReleaseObservationView.of("production", Optional.of(row("production", 126)),
			Optional.of(catalog("staging", 126)));

		assertThat(view.state()).isEqualTo(State.CATALOG_CHANNEL_DIFFERS);
		assertThat(view.isWarning()).isTrue();
	}

	@Test
	@DisplayName("관측 시각은 UTC 저장값을 Asia/Seoul yyyy-MM-dd HH:mm으로 표시한다")
	void observedAtIsFormattedInSeoul() {
		var view = ReleaseObservationView.of("production", Optional.of(row("production", 126)),
			Optional.of(catalog("production", 126)));

		assertThat(view.observedAtText()).isEqualTo("2026-10-03 18:30");
	}

	@Test
	@DisplayName("운영자 문구는 콜백·catalog·sequence 같은 개발 용어를 쓰지 않는다")
	void messagesUsePlainKorean() {
		var absent = ReleaseObservationView.of("staging", Optional.empty(), Optional.empty());
		var mismatch = ReleaseObservationView.of("production", Optional.of(row("production", 126)),
			Optional.of(catalog("production", 125)));
		var unavailable = ReleaseObservationView.of("production", Optional.of(row("production", 126)),
			Optional.empty());

		assertThat(List.of(absent.message(), mismatch.message(), unavailable.message()))
			.allSatisfy(message -> assertThat(message)
				.doesNotContain("콜백").doesNotContain("catalog").doesNotContain("sequence"));
		assertThat(absent.message()).contains("발행 완료 알림");
		assertThat(mismatch.message()).contains("공개 목록").contains("발행 번호");
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
	@DisplayName("운영 관측 행이 없으면 공개 목록을 조회하지 않고 세 채널을 순서대로 보여준다")
	void noProductionObservationSkipsCatalog() {
		var calls = new int[1];

		var views = ReleaseObservationView.all(List.of(row("staging", 7)), () -> {
			calls[0]++;
			return Optional.of(catalog("production", 1));
		});

		assertThat(views).extracting(ReleaseObservationView::channel).containsExactly("production", "staging", "dev");
		assertThat(views).extracting(ReleaseObservationView::state)
			.containsExactly(State.ABSENT, State.NOT_COMPARED, State.ABSENT);
		assertThat(calls[0]).isZero();
	}

	@Test
	@DisplayName("운영 관측이 있으면 공개 목록을 한 번만 조회해 운영 행만 비교한다")
	void productionObservationComparesOnlyProduction() {
		var calls = new int[1];

		var views = ReleaseObservationView.all(List.of(row("dev", 7), row("production", 126)), () -> {
			calls[0]++;
			return Optional.of(catalog("production", 126));
		});

		assertThat(views).extracting(ReleaseObservationView::state)
			.containsExactly(State.MATCH, State.ABSENT, State.NOT_COMPARED);
		assertThat(calls[0]).isEqualTo(1);
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
