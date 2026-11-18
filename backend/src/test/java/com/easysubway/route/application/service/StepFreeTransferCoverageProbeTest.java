package com.easysubway.route.application.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.easysubway.journey.application.JourneyAlternatives;
import com.easysubway.journey.application.JourneyRaptorQuery;
import com.easysubway.journey.application.JourneyRequest;
import com.easysubway.journey.bundle.RouteBundleSqliteRuntimeCompiler;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * 계단 없는 환승 안내 범위를 실제 서버 경로 번들로 잰다. CI에서는 돌지 않는다.
 *
 * <p>실행: {@code EASYSUBWAY_STEP_FREE_COVERAGE_BUNDLE=<server-route-bundle 디렉터리> ./gradlew test --tests
 * 'com.easysubway.route.application.service.StepFreeTransferCoverageProbeTest'}. 원본은 data 레포 Datapack candidate
 * 산출물의 {@code server-route-bundle} 디렉터리다. 운영과 같은 번들 컴파일러와 플래너를 거친다. 출력의 첫 줄(번들 식별)과
 * 모드별 분포를 읽는 측정 도구이고, 모드마다 경로가 있는 OD가 하나 이상인지만 단언한다. 무작위 OD는 시드 고정
 * (기본 1,000건, 평일 06~21시 출발, 대안 3, 환승 3)이다.</p>
 */
@EnabledIfEnvironmentVariable(named = "EASYSUBWAY_STEP_FREE_COVERAGE_BUNDLE", matches = ".+")
class StepFreeTransferCoverageProbeTest {

	private static final String ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ";

	private record Mode(String name, JourneyRequest.MobilityProfile profile, JourneyRequest.ConstraintMode constraint) {
	}

	@Test
	void measuresStepFreeCoverageOnTheRealBundle() throws Exception {
		Path root = Path.of(System.getenv("EASYSUBWAY_STEP_FREE_COVERAGE_BUNDLE"));
		int odCount = Integer.parseInt(System.getenv().getOrDefault("EASYSUBWAY_STEP_FREE_COVERAGE_OD_COUNT", "1000"));
		long seed = Long.parseLong(System.getenv().getOrDefault("EASYSUBWAY_STEP_FREE_COVERAGE_SEED", "20261008"));
		LocalDate day = LocalDate.parse(System.getenv().getOrDefault("EASYSUBWAY_STEP_FREE_COVERAGE_DAY", "2026-10-08"));
		JsonNode manifest = new ObjectMapper().readTree(root.resolve("manifest.json").toFile());
		Map<String, byte[]> payloads = new LinkedHashMap<>();
		Map<String, String> digests = new LinkedHashMap<>();
		for (String name : List.of("payload/accessibility.sqlite.zst", "payload/fare.sqlite.zst",
			"payload/timetable.sqlite.zst", "payload/topology.sqlite.zst")) {
			byte[] bytes = Files.readAllBytes(root.resolve(name));
			payloads.put(name, bytes);
			digests.put(name, sha256(bytes));
		}
		String manifestSha256 = sha256(Files.readAllBytes(root.resolve("manifest.json")));
		var runtime = new RouteBundleSqliteRuntimeCompiler().compile(new RouteBundleSqliteRuntimeCompiler.Input(
			manifestSha256, 1, manifest.get("bundleId").asText(),
			manifest.get("releaseSequence").asLong(), manifest.get("stationSetSha256").asText(), digests, payloads));
		var timetable = runtime.compiledTimetable();
		var planner = new RouteTimetableRaptorPlanner();
		TreeSet<String> stations = new TreeSet<>();
		runtime.canonicalStationLines().forEach(stationLine -> stations.add(stationLine.stationId()));
		List<String> stationList = new ArrayList<>(stations);
		// 재실행이 같은 번들을 썼는지 확인할 수 있도록 번들 식별을 남긴다. manifestSha256은 manifest.json 파일 바이트의 해시다.
		System.out.printf("bundle seq %d id %s manifestSha256 %s payloadSha256 %s stationSetSha256 %s stations %d%n",
			manifest.get("releaseSequence").asLong(), manifest.get("bundleId").asText(), manifestSha256,
			manifest.get("payloadSha256").asText(), manifest.get("stationSetSha256").asText(), stationList.size());

		List<Mode> modes = List.of(
			new Mode("STANDARD", JourneyRequest.MobilityProfile.STANDARD, JourneyRequest.ConstraintMode.NONE),
			new Mode("STEP_FREE_PREFERENCE", JourneyRequest.MobilityProfile.STEP_FREE, JourneyRequest.ConstraintMode.NONE),
			new Mode("STANDARD_REQUIRE_STEP_FREE", JourneyRequest.MobilityProfile.STANDARD,
				JourneyRequest.ConstraintMode.REQUIRE_STEP_FREE),
			new Mode("STRICT_STEP_FREE", JourneyRequest.MobilityProfile.STEP_FREE,
				JourneyRequest.ConstraintMode.REQUIRE_STEP_FREE));
		Random random = new Random(seed);
		List<String[]> ods = new ArrayList<>();
		List<Integer> readySeconds = new ArrayList<>();
		for (int index = 0; index < odCount; index++) {
			String origin = stationList.get(random.nextInt(stationList.size()));
			String destination;
			do {
				destination = stationList.get(random.nextInt(stationList.size()));
			} while (destination.equals(origin));
			ods.add(new String[] {origin, destination});
			readySeconds.add(6 * 3600 + random.nextInt(15 * 3600));
		}
		var dayStart = day.atStartOfDay(ZoneId.of("Asia/Seoul")).toInstant();
		Map<String, Map<String, Integer>> summary = new LinkedHashMap<>();
		// 표준 모드에서 계단 없음을 확정하지 못한(UNDETERMINED) 질의가 가장 빠른 여정에서 지난 미확정 환승(역|출발 노선|도착 노선)별 질의 수.
		Map<String, Integer> blockers = new TreeMap<>();
		for (Mode mode : modes) {
			Map<String, Integer> counts = new TreeMap<>();
			for (int index = 0; index < odCount; index++) {
				var query = new JourneyRaptorQuery(requestId(index), ods.get(index)[0], ods.get(index)[1],
					new JourneyRaptorQuery.DepartAt(dayStart.plusSeconds(readySeconds.get(index))),
					JourneyRequest.TimePolicy.TIMETABLE_REQUIRED, JourneyRequest.WalkingPace.STANDARD, mode.profile(),
					mode.constraint(), 3, 3, () -> false);
				var plan = planner.journeyItineraries(query, timetable);
				if (plan.itineraries().isEmpty()) {
					counts.merge("NO_ROUTE", 1, Integer::sum);
					continue;
				}
				counts.merge("ROUTE", 1, Integer::sum);
				JourneyAlternatives.StairFreeStatus status = plan.stairFreeStatus();
				counts.merge("status." + status, 1, Integer::sum);
				boolean stepFreeWithTransfer = plan.itineraries().stream()
					.anyMatch(it -> it.stairFree() && it.metrics().transfersUsed() > 0);
				boolean stepFreeDirect = plan.itineraries().stream()
					.anyMatch(it -> it.stairFree() && it.metrics().transfersUsed() == 0);
				if (mode.name().equals("STANDARD") && status == JourneyAlternatives.StairFreeStatus.UNDETERMINED) {
					var legs = plan.itineraries().getFirst().legs();
					for (int leg = 1; leg < legs.size(); leg += 2) {
						if (legs.get(leg) instanceof RouteTimetableRaptorPlanner.JourneyAccessProjection access && access.includesStairs()
							&& legs.get(leg - 1) instanceof RouteTimetableRaptorPlanner.JourneyRideProjection before
							&& legs.get(leg + 1) instanceof RouteTimetableRaptorPlanner.JourneyRideProjection after) {
							blockers.merge(access.fromStationId() + "|" + before.lineId() + "|" + after.lineId(), 1, Integer::sum);
						}
					}
				}
				// #503: 응답 reasonCodes(어댑터와 같은 accessibilityOf)가 판정과 맞는지. 여정별 분포와 OD별 (판정, 코드 집합) 교차표를 센다.
				TreeSet<String> odCodes = new TreeSet<>();
				for (var itinerary : plan.itineraries()) {
					var accessibility = JourneyRaptorAdapter.accessibilityOf(itinerary);
					assertThat(accessibility.stairFree()).isEqualTo(itinerary.stairFree());
					String code = accessibility.reasonCodes().getFirst();
					counts.merge("reason." + code, 1, Integer::sum);
					odCodes.add(code.replace("ACCESSIBILITY_", ""));
				}
				counts.merge("od." + status + "|" + String.join("+", odCodes), 1, Integer::sum);
				if (stepFreeWithTransfer) counts.merge("stepFreeWithTransfer", 1, Integer::sum);
				if (stepFreeDirect) counts.merge("stepFreeDirect", 1, Integer::sum);
				if (plan.itineraries().stream().anyMatch(it -> it.metrics().transfersUsed() > 0)) {
					counts.merge("anyItineraryWithTransfer", 1, Integer::sum);
				}
			}
			summary.put(mode.name(), counts);
			System.out.println("MODE " + mode.name() + " " + counts);
		}
		blockers.entrySet().stream().sorted(Map.Entry.<String, Integer>comparingByValue().reversed()).limit(500)
			.forEach(entry -> System.out.println("UNDETERMINED_BLOCKER " + entry.getValue() + " " + entry.getKey()));
		// 번들을 읽었는데 어느 모드에서도 경로가 하나도 없으면 측정 입력이 잘못된 것이다(날짜 밖 달력, 빈 시간표 등).
		summary.forEach((mode, counts) -> assertThat(counts.getOrDefault("ROUTE", 0))
			.as("%s 모드에서 경로가 있는 OD 수", mode).isPositive());
	}

	private static String requestId(int index) {
		char[] id = new char[26];
		id[0] = '0';
		int value = index + 1;
		for (int position = 25; position > 0; position--) {
			id[position] = ALPHABET.charAt(value & 31);
			value >>>= 5;
		}
		return new String(id);
	}

	private static String sha256(byte[] bytes) throws Exception {
		return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
	}
}
