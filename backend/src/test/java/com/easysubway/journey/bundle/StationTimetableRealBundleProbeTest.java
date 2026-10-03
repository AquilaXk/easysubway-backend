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

		System.out.printf("bundle %s seq %d freshUntil %s stationLines %d stopTimes %d%n",
			manifest.get("bundleId").asText(), manifest.get("releaseSequence").asLong(), manifest.get("freshUntil").asText(),
			runtime.canonicalStationLines().size(), runtime.stationTimetable().transitStopTimes().size());
		for (String[] probe : PROBES) {
			for (LocalDate date : List.of(LocalDate.parse("2026-10-06"), LocalDate.parse("2026-10-09"),
				LocalDate.parse("2026-10-10"), LocalDate.parse("2026-10-11"))) {
				long started = System.nanoTime();
				String outcome;
				try {
					var result = service.search(new SearchRequest(probe[0], probe[1], new Selector.ServiceDateSelector(date)));
					outcome = result.resolvedDayType() + " " + result.directionGroups().stream()
						.map(group -> group.directionName() + "=" + group.departures().size() + "편 첫차 "
							+ group.departures().getFirst().departureAt() + " " + group.departures().getFirst().servicePattern())
						.toList() + " source " + result.sourceIdentity().timetableArtifactId() + "/"
						+ result.sourceIdentity().timetableSnapshotSha256() + " freshUntil " + result.sourceIdentity().freshUntil();
				} catch (FailureException exception) {
					outcome = "FAILURE " + exception.failure();
				}
				System.out.printf("%s %s %s (%d ms): %s%n", probe[2], date, date.getDayOfWeek(),
					(System.nanoTime() - started) / 1_000_000, outcome);
			}
		}
		assertThat(runtime.canonicalStationLines()).isNotEmpty();
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
