package com.easysubway.journey.bundle;

import static org.assertj.core.api.Assertions.assertThat;

import com.easysubway.journey.application.StationTimetableSearchService;
import com.easysubway.journey.application.StationTimetableSearchService.FailureException;
import com.easysubway.journey.application.StationTimetableSearchService.SearchRequest;
import com.easysubway.journey.application.StationTimetableSearchService.Selector;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * #476: 실제 서버 경로 번들로 역 시간표를 조회해 본다. CI에서는 돌지 않는다.
 *
 * <p>실행: {@code EASYSUBWAY_STATION_TIMETABLE_REAL_BUNDLE=<server-route-bundle 디렉터리> ./gradlew test --tests
 * 'com.easysubway.journey.bundle.StationTimetableRealBundleProbeTest'}. 원본은 data 레포 Datapack candidate 산출물의
 * {@code server-route-bundle} 디렉터리다. 운영과 같은 컴파일러·활성 레지스트리·어댑터·서비스를 거친다.</p>
 */
@EnabledIfEnvironmentVariable(named = "EASYSUBWAY_STATION_TIMETABLE_REAL_BUNDLE", matches = ".+")
class StationTimetableRealBundleProbeTest {

	private static final List<String[]> PROBES = List.of(
		new String[] {"station-f497b2d7043f", "line-98718184f016", "인천 1호선 박촌"},
		new String[] {"station-gangnam", "seoul-2", "2호선 강남"},
		new String[] {"station-44dc03b65cae", "line-5b8d9b05e7e6", "대구 1호선 반월당"},
		new String[] {"station-gangnam", "seoul-4", "강남(4호선 아님: 정본 역·노선 아님)"});

	@Test
	void searchesTheRealBundleThroughTheProductionPath() throws Exception {
		Path root = Path.of(System.getenv("EASYSUBWAY_STATION_TIMETABLE_REAL_BUNDLE"));
		JsonNode manifest = new ObjectMapper().readTree(root.resolve("manifest.json").toFile());
		Map<String, byte[]> payloads = new LinkedHashMap<>();
		Map<String, String> digests = new LinkedHashMap<>();
		for (String name : List.of("payload/accessibility.sqlite.zst", "payload/fare.sqlite.zst",
			"payload/timetable.sqlite.zst", "payload/topology.sqlite.zst")) {
			byte[] bytes = Files.readAllBytes(root.resolve(name));
			payloads.put(name, bytes);
			digests.put(name, HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)));
		}
		String manifestSha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
			.digest(Files.readAllBytes(root.resolve("manifest.json"))));
		var runtime = new RouteBundleSqliteRuntimeCompiler().compile(new RouteBundleSqliteRuntimeCompiler.Input(
			manifestSha, 1, manifest.get("bundleId").asText(), manifest.get("releaseSequence").asLong(),
			manifest.get("stationSetSha256").asText(), digests, payloads));
		Instant now = Instant.parse("2026-10-06T00:00:00Z");
		var clock = Clock.fixed(now, ZoneOffset.UTC);
		var registry = new RouteBundleActivationRegistry(clock);
		registry.stage(new VerifiedRouteBundleCandidate(identity(manifest),
			new RouteBundleAdmissionEvidence(manifestSha, "probe", "probe", "probe", "probe"),
			RouteBundleServingEvidence.unobservable(), runtime, now), 0);
		registry.activate(manifestSha, 0);
		var service = new StationTimetableSearchService(new RouteBundleStationTimetableAdapter(registry), clock);
		// #476 F3: 세대 색인 보유 메모리(색인 골격 + 정본 역·노선 전체의 출발 후보)를 GC 뒤 사용 힙 차이로 잰다.
		long beforeIndex = usedHeapAfterGc();
		var index = runtime.stationTimetableIndex();
		long afterIndex = usedHeapAfterGc();

		System.out.printf("bundle %s seq %d freshUntil %s stationLines %d stopTimes %d%n",
			manifest.get("bundleId").asText(), manifest.get("releaseSequence").asLong(), manifest.get("freshUntil").asText(),
			runtime.canonicalStationLines().size(), runtime.stationTimetableIndex().timetable().transitStopTimes().size());
		for (String[] probe : PROBES) {
			for (LocalDate date : List.of(LocalDate.parse("2026-10-06"), LocalDate.parse("2026-10-09"),
				LocalDate.parse("2026-10-10"), LocalDate.parse("2026-10-11"))) {
				long started = System.nanoTime();
				String outcome;
				try {
					var result = service.search(new SearchRequest(probe[0], probe[1], new Selector.ServiceDateSelector(date)));
					outcome = result.resolvedDayType() + " " + result.directionGroups().stream()
						.map(group -> "next=" + group.nextStationId() + " directionName=" + group.directionName() + " "
							+ group.departures().size() + "편 첫 " + group.departures().getFirst().departureAt() + " "
							+ group.departures().getFirst().servicePattern() + " 종착 " + group.departures().getFirst().terminalStationId())
						.toList() + " source " + result.sourceIdentity().timetableArtifactId() + "/"
						+ result.sourceIdentity().timetableSnapshotSha256() + " freshUntil " + result.sourceIdentity().freshUntil();
				} catch (FailureException exception) {
					outcome = "FAILURE " + exception.failure();
				}
				System.out.printf("%s %s %s (%d ms): %s%n", probe[2], date, date.getDayOfWeek(),
					(System.nanoTime() - started) / 1_000_000, outcome);
			}
		}
		// 정본 역·노선 전체를 같은 날짜로 조회해 결과 분포를 남긴다.
		Map<String, Integer> outcomes = new java.util.TreeMap<>();
		for (var stationLine : runtime.canonicalStationLines()) {
			for (String extraDate : List.of("2026-10-03", "2026-10-09", "2026-10-10")) {
				String extraKey;
				try {
					service.search(new SearchRequest(stationLine.stationId(), stationLine.lineId(),
						new Selector.ServiceDateSelector(LocalDate.parse(extraDate))));
					extraKey = extraDate + " OK";
				} catch (FailureException exception) {
					extraKey = extraDate + " " + exception.failure() + (exception.detail().name().equals("NONE") ? "" : ":" + exception.detail());
				}
				outcomes.merge(extraKey, 1, Integer::sum);
			}
			String key;
			try {
				var result = service.search(new SearchRequest(stationLine.stationId(), stationLine.lineId(),
					new Selector.ServiceDateSelector(LocalDate.parse("2026-10-06"))));
				key = "OK groups=" + result.directionGroups().size();
			} catch (FailureException exception) {
				key = "FAILURE " + exception.failure();
				System.out.println("failure " + exception.failure() + " " + stationLine);
			}
			outcomes.merge(key, 1, Integer::sum);
		}
		System.out.println("2026-10-06 all canonical station-lines: " + outcomes);
		long afterAllCandidates = usedHeapAfterGc();
		System.out.printf("INDEX MEMORY skeleton=%.1fMiB withAllStationLineCandidates=%.1fMiB (same index instance reused: %s)%n",
			(afterIndex - beforeIndex) / 1048576.0, (afterAllCandidates - beforeIndex) / 1048576.0,
			index == runtime.stationTimetableIndex());
		assertThat(runtime.canonicalStationLines()).isNotEmpty();
	}

	private static long usedHeapAfterGc() throws InterruptedException {
		Runtime runtime = Runtime.getRuntime();
		for (int attempt = 0; attempt < 3; attempt++) {
			System.gc();
			Thread.sleep(200);
		}
		return runtime.totalMemory() - runtime.freeMemory();
	}

	private static RouteBundleIdentity identity(JsonNode manifest) {
		return new RouteBundleIdentity(
			manifest.get("manifestVersion").asInt(), manifest.get("artifactKind").asText(), manifest.get("bundleId").asText(),
			manifest.get("releaseSequence").asLong(), manifest.get("stationSetSha256").asText(),
			manifest.get("payloadSha256").asText(), manifest.get("topologySha256").asText(),
			manifest.get("timetableSha256").asText(), manifest.get("accessibilitySha256").asText(),
			manifest.get("fareSha256").asText(), manifest.get("provenanceSha256").asText(),
			manifest.get("compatibilitySha256").asText(), manifest.get("serviceTimezone").asText(),
			manifest.get("activeFrom").asText(), manifest.get("freshUntil").asText(),
			new RouteBundleIdentity.SchemaCompatibility(
				manifest.get("schemaCompatibility").get("backendMin").asInt(),
				manifest.get("schemaCompatibility").get("backendMax").asInt()),
			manifest.get("keyId").asText(),
			new RouteBundleIdentity.Signature(
				manifest.get("signature").get("algorithm").asText(), manifest.get("signature").get("value").asText()));
	}
}
