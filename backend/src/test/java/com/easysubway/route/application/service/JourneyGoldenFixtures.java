package com.easysubway.route.application.service;

import com.easysubway.route.application.port.out.LoadRouteTimetablePort.RouteTimetable;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.Set;
import java.util.TreeSet;

/**
 * golden이 묶이는 고정 입력. 실데이터나 현재 시각에는 묶지 않는다.
 *
 * <ul>
 *   <li>{@code capital}: sha256으로 고정한 수도권 평일 실데이터 fixture({@link CapitalRealDerivedFixture}).</li>
 *   <li>{@code synthetic-<seed>}: 시드 고정 합성 번들({@link JourneyEngineSyntheticBundles}). 계단 없는 동선이
 *   확정된 환승과 시설 차단을 다루는 데 쓴다. 생성기가 바뀌면 {@link Fixture#pin()}이 달라져 golden이 먼저 알린다.</li>
 * </ul>
 */
final class JourneyGoldenFixtures {

	static final String CAPITAL = "capital";
	private static final String SYNTHETIC_PREFIX = "synthetic-";

	private JourneyGoldenFixtures() {
	}

	/** {@code pin}은 입력 내용을 가리키는 지문이다. golden 머리에 적어 입력이 달라졌음을 먼저 드러낸다. */
	record Fixture(String id, RouteTimetable timetable, LocalDate serviceDate, String pin) {

		Set<String> stationIds() {
			Set<String> stations = new TreeSet<>();
			timetable.transitStopTimes().forEach(stop -> stations.add(stop.stationId()));
			return stations;
		}
	}

	static Fixture load(String id) {
		if (CAPITAL.equals(id)) {
			return new Fixture(id, CapitalRealDerivedFixture.load(), CapitalRealDerivedFixture.SERVICE_DATE,
				CapitalRealDerivedFixture.FIXTURE_SHA256);
		}
		if (id.startsWith(SYNTHETIC_PREFIX)) {
			var bundle = JourneyEngineSyntheticBundles.generate(Long.parseLong(id.substring(SYNTHETIC_PREFIX.length())));
			return new Fixture(id, bundle.timetable(), bundle.queryDate(),
				sha256(JourneyEngineSyntheticBundles.describe(bundle.timetable())));
		}
		throw new IllegalArgumentException("unknown golden fixture: " + id);
	}

	static String sha256(String text) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
		} catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException(exception);
		}
	}
}
