package com.easysubway.collection.adapter.out.source;

import com.easysubway.collection.application.port.out.FetchTransitMasterCollectionSourcePort;
import com.easysubway.collection.application.port.out.TransitMasterCollectionSnapshot;
import com.easysubway.transit.application.port.out.LoadTransitMasterPort;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
class LoadedTransitMasterCollectionSourceAdapter implements FetchTransitMasterCollectionSourcePort {

	private final LoadTransitMasterPort loadTransitMasterPort;

	LoadedTransitMasterCollectionSourceAdapter(LoadTransitMasterPort loadTransitMasterPort) {
		this.loadTransitMasterPort = loadTransitMasterPort;
	}

	@Override
	public TransitMasterCollectionSnapshot fetch() {
		var operators = loadTransitMasterPort.loadOperators();
		var lines = loadTransitMasterPort.loadLines();
		var stations = loadTransitMasterPort.loadStations();
		var stationLines = loadTransitMasterPort.loadStationLines();
		var exits = loadTransitMasterPort.loadStationExits();
		var facilities = loadTransitMasterPort.loadAccessibilityFacilities();
		String payload = checksumPayload(operators, lines, stations, stationLines, exits, facilities);
		return new TransitMasterCollectionSnapshot(
			"load-transit-master-port://official-current",
			"transit-master://loaded-current",
			sha256(payload),
			operators.size() + lines.size() + stations.size() + stationLines.size() + exits.size() + facilities.size()
		);
	}

	private static String checksumPayload(
		List<?> operators,
		List<?> lines,
		List<?> stations,
		List<?> stationLines,
		List<?> exits,
		List<?> facilities
	) {
		var payload = new StringBuilder();
		appendRecords(payload, "operators", operators);
		appendRecords(payload, "lines", lines);
		appendRecords(payload, "stations", stations);
		appendRecords(payload, "stationLines", stationLines);
		appendRecords(payload, "exits", exits);
		appendRecords(payload, "facilities", facilities);
		return payload.toString();
	}

	private static void appendRecords(StringBuilder payload, String section, List<?> records) {
		payload.append(section).append('=').append(records.size()).append('\n');
		records.stream()
			.map(String::valueOf)
			.sorted()
			.forEach(record -> payload.append(record).append('\n'));
	}

	private static String sha256(String payload) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
				.digest(payload.getBytes(StandardCharsets.UTF_8)));
		} catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("SHA-256 digest is unavailable.", exception);
		}
	}
}
