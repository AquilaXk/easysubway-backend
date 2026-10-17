package com.easysubway.journey.bundle;

import com.easysubway.route.application.port.out.LoadRouteTimetablePort.RouteTimetable;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * #514: {@code server-route-bundle} 디렉터리(manifest.json과 payload/*.sqlite.zst)를 운영과 같은 SQLite 컴파일러로
 * 읽어 시간표로 바꾼다. golden 회귀의 번들 diff 도구가 임의 후보 번들을 읽는 데 쓴다.
 *
 * <p>payload 파일의 sha256이 manifest의 digest와 다르면 읽지 않는다. 서명 검증은 하지 않는다. 이 도구는 후보 번들의
 * 경로 결과를 비교하는 진단용이고 번들을 서빙하거나 승격하지 않는다.</p>
 */
public final class JourneyGoldenBundleLoader {

	private static final String MANIFEST = "manifest.json";
	/** payload 경로와 그 digest를 담은 manifest 키. */
	private static final Map<String, String> PAYLOAD_DIGEST_KEYS = Map.of(
		RouteBundleSqliteRuntimeCompiler.TOPOLOGY_PATH, "topologySha256",
		RouteBundleSqliteRuntimeCompiler.TIMETABLE_PATH, "timetableSha256",
		RouteBundleSqliteRuntimeCompiler.ACCESSIBILITY_PATH, "accessibilitySha256",
		RouteBundleSqliteRuntimeCompiler.FARE_PATH, "fareSha256");
	private static final long GENERATION = 1;

	private JourneyGoldenBundleLoader() {
	}

	/** {@code payloadSha256}은 manifest가 가리키는 번들 전체 지문이다. 보고서에서 어느 번들인지 구분하는 데 쓴다. */
	public record Loaded(RouteTimetable timetable, String bundleId, long releaseSequence, String payloadSha256) {
	}

	public static Loaded load(Path directory) {
		try {
			JsonNode manifest = new ObjectMapper().readTree(regularFile(directory.resolve(MANIFEST)).toFile());
			Map<String, byte[]> payloads = new LinkedHashMap<>();
			Map<String, String> digests = new LinkedHashMap<>();
			for (Map.Entry<String, String> entry : PAYLOAD_DIGEST_KEYS.entrySet()) {
				byte[] bytes = Files.readAllBytes(regularFile(directory.resolve(entry.getKey())));
				String digest = sha256(bytes);
				if (!digest.equals(manifest.path(entry.getValue()).asText())) {
					throw new IllegalArgumentException(entry.getKey() + " digest does not match " + entry.getValue());
				}
				payloads.put(entry.getKey(), bytes);
				digests.put(entry.getKey(), digest);
			}
			String payloadSha256 = manifest.path("payloadSha256").asText();
			var input = new RouteBundleSqliteRuntimeCompiler.Input(payloadSha256, GENERATION,
				manifest.path("bundleId").asText(), manifest.path("releaseSequence").asLong(),
				manifest.path("stationSetSha256").asText(), digests, payloads);
			return new Loaded(new RouteBundleSqliteRuntimeCompiler().readTimetable(input), input.bundleId(),
				input.releaseSequence(), payloadSha256);
		} catch (IOException exception) {
			throw new UncheckedIOException(exception);
		}
	}

	private static Path regularFile(Path path) {
		if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
			throw new IllegalArgumentException("route bundle file is missing or not a regular file: " + path.getFileName());
		}
		return path;
	}

	private static String sha256(byte[] bytes) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
		} catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException(exception);
		}
	}
}
