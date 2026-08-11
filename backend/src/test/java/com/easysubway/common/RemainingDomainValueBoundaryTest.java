package com.easysubway.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.easysubway.field.domain.FieldVerificationItem;
import com.easysubway.field.domain.FieldVerificationItemType;
import com.easysubway.field.domain.FieldVerificationSession;
import com.easysubway.field.domain.FieldVerificationStatus;
import com.easysubway.notification.application.port.in.ResendPushNotificationsCommand;
import com.easysubway.transit.domain.DataQualityLevel;
import com.easysubway.transit.domain.DataSourceType;
import com.easysubway.transit.domain.SimplifiedStationLayout;
import com.easysubway.transit.domain.SimplifiedStationLayoutConfidence;
import com.easysubway.transit.domain.SimplifiedStationLayoutStatus;
import com.easysubway.transit.domain.Station;
import com.easysubway.transit.domain.StationLineSummary;
import com.easysubway.transit.domain.StationWithLines;
import com.easysubway.transit.domain.TransitRegionSummary;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RemainingDomainValueBoundaryTest {

	@Test
	void fieldVerificationItemsAreFreshUnmodifiableSnapshots() {
		var first = fieldItem("item-1", FieldVerificationItemType.ELEVATOR);
		var second = fieldItem("item-2", FieldVerificationItemType.RESTROOM);
		var input = new ArrayList<>(List.of(first, second));
		var session = fieldSession(input);
		input.add(fieldItem("item-3", FieldVerificationItemType.EXIT));

		assertThat(session.items()).containsExactly(first, second);
		assertThat(session.items()).isNotSameAs(session.items());
		assertThatThrownBy(() -> session.items().add(fieldItem("item-4", FieldVerificationItemType.ESCALATOR)))
			.isInstanceOf(UnsupportedOperationException.class);
		assertThat(session).isEqualTo(fieldSession(List.of(first, second)));
		assertThatThrownBy(() -> fieldSession(null))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessage("items must not be empty.");
		assertThatThrownBy(() -> fieldSession(List.of()))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessage("items must not be empty.");
		var withNull = new ArrayList<FieldVerificationItem>();
		withNull.add(first);
		withNull.add(null);
		withNull.add(second);
		assertThatThrownBy(() -> fieldSession(withNull))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessage("items must not be null.");
	}

	@Test
	void resendNotificationIdsAreFreshUnmodifiableNormalizedSnapshots() {
		var input = new ArrayList<String>();
		input.add(" push-1 ");
		input.add(null);
		input.add(" ");
		input.add("push-2");
		input.add("push-1");
		var command = new ResendPushNotificationsCommand(input, 5);
		input.add("push-3");

		assertThat(command.notificationIds()).containsExactly("push-1", "push-2");
		assertThat(command.notificationIds()).isNotSameAs(command.notificationIds());
		assertThatThrownBy(() -> command.notificationIds().add("push-3"))
			.isInstanceOf(UnsupportedOperationException.class);
		assertThat(command).isEqualTo(new ResendPushNotificationsCommand(List.of("push-1", "push-2"), 5));
		assertThat(new ResendPushNotificationsCommand(null, 5).notificationIds()).isEmpty();
		assertThat(new ResendPushNotificationsCommand(List.of(), 5).notificationIds()).isEmpty();
	}

	@Test
	void simplifiedLayoutSourceIdsAreFreshUnmodifiableSnapshots() {
		var input = new ArrayList<>(List.of(" source-1 ", "source-1", "source-2"));
		var layout = layout(input);
		input.add("source-3");

		assertThat(layout.sourceIds()).containsExactly("source-1", "source-2");
		assertThat(layout.sourceIds()).isNotSameAs(layout.sourceIds());
		assertThatThrownBy(() -> layout.sourceIds().add("source-3"))
			.isInstanceOf(UnsupportedOperationException.class);
		assertThat(layout).isEqualTo(layout(List.of("source-1", "source-2")));
		assertThatThrownBy(() -> layout(null))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessage("sourceIds must not be empty.");
		assertThatThrownBy(() -> layout(List.of()))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessage("sourceIds must not be empty.");
		var withNull = new ArrayList<String>();
		withNull.add("source-1");
		withNull.add(null);
		withNull.add("source-2");
		assertThatThrownBy(() -> layout(withNull))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessage("sourceIds must not be blank.");
	}

	@Test
	void stationLinesPreserveNullableMutableCopyContractWithoutAliasing() {
		var first = line("line-1");
		var second = line("line-2");
		var third = line("line-3");
		var input = new ArrayList<StationLineSummary>();
		input.add(first);
		input.add(null);
		input.add(second);
		var station = new StationWithLines(station(), input);
		input.add(third);

		assertThat(station.lines()).containsExactly(first, null, second);
		assertThat(station.lines()).isNotSameAs(station.lines());
		var returned = station.lines();
		returned.clear();
		returned.add(third);
		assertThat(station.lines()).containsExactly(first, null, second);
		var equalLines = new ArrayList<StationLineSummary>();
		equalLines.add(first);
		equalLines.add(null);
		equalLines.add(second);
		assertThat(station).isEqualTo(new StationWithLines(station(), equalLines));
		assertThat(new StationWithLines(station(), null).lines()).isNull();
		var empty = new StationWithLines(station(), List.of()).lines();
		assertThat(empty).isEmpty();
		empty.add(first);
	}

	@Test
	void regionQualityCountsAreFreshUnmodifiableEnumMapSnapshots() {
		var input = new EnumMap<DataQualityLevel, Long>(DataQualityLevel.class);
		input.put(DataQualityLevel.LEVEL_1, 2L);
		input.put(DataQualityLevel.LEVEL_2, null);
		input.put(DataQualityLevel.LEVEL_3, 4L);
		var summary = new TransitRegionSummary("수도권", 1, 2, 3, input);
		input.put(DataQualityLevel.LEVEL_4, 8L);

		assertThat(summary.dataQualityCounts().keySet()).containsExactly(
			DataQualityLevel.LEVEL_1,
			DataQualityLevel.LEVEL_2,
			DataQualityLevel.LEVEL_3
		);
		assertThat(summary.dataQualityCounts())
			.containsEntry(DataQualityLevel.LEVEL_1, 2L)
			.containsEntry(DataQualityLevel.LEVEL_3, 4L)
			.containsKey(DataQualityLevel.LEVEL_2);
		assertThat(summary.dataQualityCounts().get(DataQualityLevel.LEVEL_2)).isNull();
		assertThat(summary.dataQualityCounts()).isNotSameAs(summary.dataQualityCounts());
		assertThatThrownBy(() -> summary.dataQualityCounts().put(DataQualityLevel.LEVEL_4, 8L))
			.isInstanceOf(UnsupportedOperationException.class);
		var equalCounts = new EnumMap<DataQualityLevel, Long>(DataQualityLevel.class);
		equalCounts.put(DataQualityLevel.LEVEL_1, 2L);
		equalCounts.put(DataQualityLevel.LEVEL_2, null);
		equalCounts.put(DataQualityLevel.LEVEL_3, 4L);
		assertThat(summary).isEqualTo(new TransitRegionSummary(
			"수도권", 1, 2, 3, equalCounts));

		var empty = new TransitRegionSummary("수도권", 0, 0, 0, null);
		assertThat(empty.dataQualityCounts()).isEmpty();
		assertThat(empty.dataQualityCounts()).isNotSameAs(empty.dataQualityCounts());
		assertThatThrownBy(() -> empty.dataQualityCounts().put(DataQualityLevel.LEVEL_1, 1L))
			.isInstanceOf(UnsupportedOperationException.class);

		var nonNullEmpty = new TransitRegionSummary("수도권", 0, 0, 0, Map.of());
		assertThat(nonNullEmpty.dataQualityCounts()).isEmpty();
		assertThat(nonNullEmpty.dataQualityCounts()).isNotSameAs(nonNullEmpty.dataQualityCounts());
		assertThatThrownBy(() -> nonNullEmpty.dataQualityCounts().put(DataQualityLevel.LEVEL_1, 1L))
			.isInstanceOf(UnsupportedOperationException.class);
	}

	private static FieldVerificationSession fieldSession(List<FieldVerificationItem> items) {
		return new FieldVerificationSession(
			"session-1",
			"station-1",
			"상록수역",
			LocalDate.of(2026, 8, 10),
			"field-team",
			FieldVerificationStatus.PLANNED,
			null,
			items
		);
	}

	private static FieldVerificationItem fieldItem(String id, FieldVerificationItemType type) {
		return new FieldVerificationItem(
			id,
			type,
			"엘리베이터",
			FieldVerificationStatus.PLANNED,
			null
		);
	}

	private static SimplifiedStationLayout layout(List<String> sourceIds) {
		return new SimplifiedStationLayout(
			"layout-1",
			"station-1",
			1,
			SimplifiedStationLayoutStatus.DRAFT,
			sourceIds,
			SimplifiedStationLayoutConfidence.OFFICIAL_DIAGRAM_REFERENCED,
			"B1",
			"{\"nodes\":[],\"edges\":[]}",
			null,
			"admin-user",
			null,
			null,
			LocalDate.of(2026, 8, 10)
		);
	}

	private static Station station() {
		return new Station(
			"station-1",
			"상록수",
			"Sangnoksu",
			"수도권",
			BigDecimal.valueOf(37.302),
			BigDecimal.valueOf(126.866),
			DataQualityLevel.LEVEL_1,
			DataSourceType.ADMIN_VERIFIED,
			LocalDate.of(2026, 8, 10),
			true
		);
	}

	private static StationLineSummary line(String id) {
		return new StationLineSummary(id, "operator-1", id, "#000000", "101", 1, "상대식");
	}
}
