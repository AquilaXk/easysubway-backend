package com.easysubway.journey.bundle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JourneyProfileMeasurementInputsTest {
	private static final ObjectMapper JSON = new ObjectMapper();

	@Test
	void pinsOnlyTheWorkflowSelectedCandidateInputsAndKeepsTheRouteTupleDistinctFromTheManifest(@TempDir Path temp) throws Exception {
		Fixture fixture = fixture(temp);
		var pinned = JourneyProfileMeasurementInputs.read(fixture.root(), fixture.input());

		assertThat(pinned.measurementInput().routeBundleSha256()).isEqualTo(fixture.routeTupleSha());
		assertThat(pinned.routeManifestSha256()).isEqualTo(fixture.routeManifestSha());
		assertThat(pinned.componentMetadata().manifestSha256()).isNotEqualTo(fixture.routeManifestSha());
		assertThat(pinned.routeEntries()).hasSize(8);
		byte[] copy = pinned.fanInBytes(); copy[0] ^= 1;
		assertThat(pinned.fanInBytes()).isNotEqualTo(copy);
	}

	@Test
	void rejectsTamperedSelectedBytesInputInventoryMismatchAndMalformedJson(@TempDir Path temp) throws Exception {
		Fixture first = fixture(temp);
		Files.writeString(first.root().resolve("server-route-bundle/manifest.json"), "changed");
		assertThatThrownBy(() -> JourneyProfileMeasurementInputs.read(first.root(), first.input()))
			.isInstanceOf(IllegalArgumentException.class);
		Fixture second = fixture(temp.resolve("second"));
		Files.writeString(second.input(), Files.readString(second.input()).replace(second.inventorySha(), "0".repeat(64)));
		assertThatThrownBy(() -> JourneyProfileMeasurementInputs.read(second.root(), second.input()))
			.isInstanceOf(IllegalArgumentException.class);
		Fixture third = fixture(temp.resolve("third"));
		Files.writeString(third.input(), "{\"schemaVersion\":1,\"schemaVersion\":1}");
		assertThatThrownBy(() -> JourneyProfileMeasurementInputs.read(third.root(), third.input()))
			.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void rejectsTrailingTokensDuplicateFieldsAndWorkflowTypeOrOrderDrift(@TempDir Path temp) throws Exception {
		Fixture fixture = fixture(temp);
		String original = Files.readString(fixture.input());
		var numericRun = (com.fasterxml.jackson.databind.node.ObjectNode) JSON.readTree(original);
		numericRun.put("dataRunId", 7);
		var reversedQueries = (com.fasterxml.jackson.databind.node.ObjectNode) JSON.readTree(original);
		reversedQueries.putArray("queryClasses").add("TYPED_FAILURE").add("CUTOFF")
			.add("LAST_CONNECTION").add("ARRIVE_BY").add("DEPARTURE_PROFILE").add("POINT");
		for (String invalid : List.of(original + " {}",
			original.replaceFirst("\\{", "{\"schemaVersion\":1,"),
			JSON.writeValueAsString(numericRun), JSON.writeValueAsString(reversedQueries))) {
			Files.writeString(fixture.input(), invalid);
			assertThatThrownBy(() -> JourneyProfileMeasurementInputs.read(fixture.root(), fixture.input()))
				.isInstanceOf(IllegalArgumentException.class);
		}
	}

	@Test
	void rejectsSymlinkedCandidateInputsAndTraversalInTheClosedInput(@TempDir Path temp) throws Exception {
		Fixture symlinkFixture = fixture(temp);
		Path manifest = symlinkFixture.root().resolve("server-route-bundle/manifest.json");
		Files.delete(manifest);
		Files.createSymbolicLink(manifest, symlinkFixture.root().resolve("server-route-bundle/provenance.json"));
		assertThatThrownBy(() -> JourneyProfileMeasurementInputs.read(symlinkFixture.root(), symlinkFixture.input()))
			.isInstanceOf(IllegalArgumentException.class);
		Fixture traversalFixture = fixture(temp.resolve("traversal"));
		Files.writeString(traversalFixture.input(), Files.readString(traversalFixture.input()).replace(
			"tools/datapack/release/current-five-region-source-fan-in.json", "../outside"));
		assertThatThrownBy(() -> JourneyProfileMeasurementInputs.read(traversalFixture.root(), traversalFixture.input()))
			.isInstanceOf(IllegalArgumentException.class);
	}

	private static Fixture fixture(Path parent) throws Exception {
		Path root = Files.createDirectories(parent.resolve("candidate"));
		Map<String, byte[]> selected = new LinkedHashMap<>();
		selected.put("tools/datapack/release/current-five-region-source-fan-in.json", "fan-in".getBytes());
		for (String path : List.of("compatibility.json", "manifest.json", "manifest.signing-input.json",
			"payload/accessibility.sqlite.zst", "payload/fare.sqlite.zst", "payload/timetable.sqlite.zst",
			"payload/topology.sqlite.zst", "provenance.json")) selected.put("server-route-bundle/" + path, path.getBytes());
		List<Map<String, Object>> entries = new ArrayList<>();
		for (var item : selected.entrySet()) {
			Path file = root.resolve(item.getKey()); Files.createDirectories(file.getParent()); Files.write(file, item.getValue());
			entries.add(Map.of("path", item.getKey(), "sizeBytes", item.getValue().length, "sha256", sha(item.getValue())));
		}
		entries.sort(java.util.Comparator.comparing(entry -> (String) entry.get("path")));
		byte[] inventory = JSON.writeValueAsBytes(Map.of("schemaVersion", 1, "artifactKind", "datapack-candidate-inventory", "entries", entries));
		Files.write(root.resolve("data-artifact-inventory.json"), inventory);
		String inventorySha = sha(inventory); String routeManifestSha = sha(selected.get("server-route-bundle/manifest.json"));
		byte[] component = JSON.writeValueAsBytes(Map.ofEntries(
			Map.entry("schemaVersion", 1), Map.entry("component", "data"), Map.entry("repository", "AquilaXk/easysubway-data"),
			Map.entry("gitSha", "a".repeat(40)), Map.entry("workflowRunId", 7), Map.entry("dataVersion", "v"),
			Map.entry("releaseSequence", 1), Map.entry("manifestSha256", "9".repeat(64)),
			Map.entry("provenance", Map.of("sourceSnapshotSetHash", "b".repeat(64))),
			Map.entry("artifactInventorySha256", inventorySha), Map.entry("contractVersion", "datapack-contract-v3"), Map.entry("issueRef", "#6")));
		Files.write(root.resolve("data-component-manifest.json"), component);
		List<Map<String, Object>> route = entries.stream().filter(entry -> ((String) entry.get("path")).startsWith("server-route-bundle/")).toList();
		String routeTuple = sha(JSON.writeValueAsBytes(route.stream().map(entry -> new java.util.TreeMap<>(entry)).toList()));
		Map<String, Object> input = new LinkedHashMap<>();
		input.put("schemaVersion", 1); input.put("artifactKind", "journey-profile-predeployment-measurement-input"); input.put("backendHeadSha", "c".repeat(40)); input.put("dataRepository", "AquilaXk/easysubway-data"); input.put("dataHeadSha", "a".repeat(40)); input.put("dataRunId", "7"); input.put("regionIds", List.of("busan", "capital", "daegu", "daejeon", "gwangju")); input.put("queryClasses", List.of("POINT", "DEPARTURE_PROFILE", "ARRIVE_BY", "LAST_CONNECTION", "CUTOFF", "TYPED_FAILURE")); input.put("fanIn", Map.of("path", "tools/datapack/release/current-five-region-source-fan-in.json", "sha256", sha(selected.get("tools/datapack/release/current-five-region-source-fan-in.json")))); input.put("routeBundleSha256", routeTuple); input.put("regionalMatrixSha256", "d".repeat(64)); input.put("componentSha256", sha(component)); input.put("inventorySha256", inventorySha); input.put("releaseEvidenceSha256", "e".repeat(64)); input.put("releaseDecisionSha256", "f".repeat(64));
		Path inputPath = parent.resolve("input.json"); Files.write(inputPath, JSON.writeValueAsBytes(input));
		return new Fixture(root, inputPath, inventorySha, routeTuple, routeManifestSha);
	}

	private static String sha(byte[] bytes) { try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); } catch (Exception exception) { throw new AssertionError(exception); } }
	private record Fixture(Path root, Path input, String inventorySha, String routeTupleSha, String routeManifestSha) { }
}
