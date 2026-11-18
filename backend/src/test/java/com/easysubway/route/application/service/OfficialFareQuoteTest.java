package com.easysubway.route.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class OfficialFareQuoteTest {

	private static OfficialFareQuote quote(
		String origin, String destination, String sourceId, String snapshotId, String hash,
		int gnrlCard, int gnrlCash, int yungCard, int yungCash, int childCard, int childCash
	) {
		return new OfficialFareQuote(origin, destination, sourceId, snapshotId, hash,
			gnrlCard, gnrlCash, yungCard, yungCash, childCard, childCash);
	}

	@Test
	void keepsSixOfficialAmountsAndProvenance() {
		var quote = quote("station-a", "station-b", "official", "snapshot-1", "f".repeat(64),
			1400, 1500, 800, 900, 500, 600);

		assertThat(quote.originStationId()).isEqualTo("station-a");
		assertThat(quote.destinationStationId()).isEqualTo("station-b");
		assertThat(quote.snapshotId()).isEqualTo("snapshot-1");
		assertThat(quote.gnrlCashFare()).isEqualTo(1500);
		assertThat(quote.childCashFare()).isEqualTo(600);
	}

	@Test
	void acceptsZeroAmountsButRejectsAnyNegativeAmount() {
		assertThat(quote("a", "b", "s", "n", "h", 0, 0, 0, 0, 0, 0).gnrlCardFare()).isZero();
		assertThatThrownBy(() -> quote("a", "b", "s", "n", "h", -1, 0, 0, 0, 0, 0))
			.isInstanceOf(IllegalArgumentException.class).hasMessage("fare must not be negative");
		assertThatThrownBy(() -> quote("a", "b", "s", "n", "h", 0, 0, 0, 0, 0, -1))
			.isInstanceOf(IllegalArgumentException.class).hasMessage("fare must not be negative");
		assertThatThrownBy(() -> quote("a", "b", "s", "n", "h", 0, -1, 0, 0, 0, 0))
			.hasMessage("fare must not be negative");
		assertThatThrownBy(() -> quote("a", "b", "s", "n", "h", 0, 0, -1, 0, 0, 0))
			.hasMessage("fare must not be negative");
		assertThatThrownBy(() -> quote("a", "b", "s", "n", "h", 0, 0, 0, -1, 0, 0))
			.hasMessage("fare must not be negative");
		assertThatThrownBy(() -> quote("a", "b", "s", "n", "h", 0, 0, 0, 0, -1, 0))
			.hasMessage("fare must not be negative");
	}

	@Test
	void rejectsBlankOrIdenticalStationsAndBlankSourceOrSnapshot() {
		assertThatThrownBy(() -> quote(null, "b", "s", "n", "h", 1, 1, 1, 1, 1, 1))
			.hasMessage("originStationId must not be blank");
		assertThatThrownBy(() -> quote(" ", "b", "s", "n", "h", 1, 1, 1, 1, 1, 1))
			.hasMessage("originStationId must not be blank");
		assertThatThrownBy(() -> quote("a", null, "s", "n", "h", 1, 1, 1, 1, 1, 1))
			.hasMessage("destinationStationId must not be blank");
		assertThatThrownBy(() -> quote("a", "", "s", "n", "h", 1, 1, 1, 1, 1, 1))
			.hasMessage("destinationStationId must not be blank");
		assertThatThrownBy(() -> quote("a", "a", "s", "n", "h", 1, 1, 1, 1, 1, 1))
			.hasMessage("originStationId and destinationStationId must differ");
		assertThatThrownBy(() -> quote("a", "b", null, "n", "h", 1, 1, 1, 1, 1, 1))
			.hasMessage("sourceId must not be blank");
		assertThatThrownBy(() -> quote("a", "b", " ", "n", "h", 1, 1, 1, 1, 1, 1))
			.hasMessage("sourceId must not be blank");
		assertThatThrownBy(() -> quote("a", "b", "s", null, "h", 1, 1, 1, 1, 1, 1))
			.hasMessage("snapshotId must not be blank");
		assertThatThrownBy(() -> quote("a", "b", "s", " ", "h", 1, 1, 1, 1, 1, 1))
			.hasMessage("snapshotId must not be blank");
		assertThatThrownBy(() -> quote("a", "b", "s", "n", null, 1, 1, 1, 1, 1, 1))
			.isInstanceOf(NullPointerException.class).hasMessage("mappingLedgerHash");
	}

	@Test
	void buildsDirectionalFareKeyAndRejectsBlankEndpoints() {
		assertThat(OfficialFareQuote.fareKey("a", "b")).isEqualTo("a^@b");
		assertThat(OfficialFareQuote.fareKey("a", "b")).isNotEqualTo(OfficialFareQuote.fareKey("b", "a"));
		assertThatThrownBy(() -> OfficialFareQuote.fareKey(null, "b")).hasMessage("origin must not be blank");
		assertThatThrownBy(() -> OfficialFareQuote.fareKey(" ", "b")).hasMessage("origin must not be blank");
		assertThatThrownBy(() -> OfficialFareQuote.fareKey("a", null)).hasMessage("destination must not be blank");
		assertThatThrownBy(() -> OfficialFareQuote.fareKey("a", " ")).hasMessage("destination must not be blank");
	}
}
