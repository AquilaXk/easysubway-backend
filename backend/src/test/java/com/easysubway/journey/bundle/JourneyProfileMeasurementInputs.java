package com.easysubway.journey.bundle;

import com.easysubway.route.application.service.RaptorRouteBundleRuntimeView;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Strict reader for the workflow-produced measurement input; it does not admit a serving bundle. */
final class JourneyProfileMeasurementInputs {
	private static final ObjectMapper JSON = new ObjectMapper(JsonFactory.builder()
		.enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
		.enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
	private static final List<String> ROUTE_PATHS = List.of(
		"compatibility.json", "manifest.json", "manifest.signing-input.json",
		"payload/accessibility.sqlite.zst", "payload/fare.sqlite.zst", "payload/timetable.sqlite.zst",
		"payload/topology.sqlite.zst", "provenance.json");
	private static final List<String> REGION_IDS = List.of("busan", "capital", "daegu", "daejeon", "gwangju");
	private static final List<String> QUERY_CLASSES = List.of(
		"POINT", "DEPARTURE_PROFILE", "ARRIVE_BY", "LAST_CONNECTION", "CUTOFF", "TYPED_FAILURE");
	private static final String FAN_IN_PATH = "tools/datapack/release/current-five-region-source-fan-in.json";

	private JourneyProfileMeasurementInputs() {
	}

	static PinnedInputs read(Path candidateRoot, Path measurementInput) throws IOException {
		Path root = requireDirectory(candidateRoot, "candidate root");
		MeasurementInput input = input(parse(absoluteRegular(measurementInput, "measurement input"), "measurement input"));
		byte[] inventoryBytes = regular(root, "data-artifact-inventory.json");
		if (!sha256(inventoryBytes).equals(input.inventorySha256())) fail("measurement input inventory binding");
		List<InventoryEntry> inventory = inventory(parse(inventoryBytes, "candidate inventory"));
		byte[] componentBytes = regular(root, "data-component-manifest.json");
		ComponentMetadata component = component(parse(componentBytes, "component manifest"), input, inventoryBytes);
		if (!sha256(componentBytes).equals(input.componentSha256())) fail("measurement input component binding");

		InventoryEntry fanInEntry = exactlyOne(inventory, FAN_IN_PATH);
		if (!input.fanIn().path().equals(FAN_IN_PATH) || !input.fanIn().sha256().equals(fanInEntry.sha256())) fail("fan-in binding");
		byte[] fanInBytes = verified(root, fanInEntry);
		List<PinnedRouteEntry> routeEntries = new ArrayList<>();
		for (String routePath : ROUTE_PATHS) {
			InventoryEntry entry = exactlyOne(inventory, "server-route-bundle/" + routePath);
			routeEntries.add(new PinnedRouteEntry(entry.path(), entry.sizeBytes(), entry.sha256(), verified(root, entry)));
		}
		if (inventory.stream().filter(entry -> entry.path().startsWith("server-route-bundle/")).count() != ROUTE_PATHS.size()) {
			fail("server route bundle inventory");
		}
		String routeBundleSha256 = sha256(canonicalRouteEntries(routeEntries));
		if (!routeBundleSha256.equals(input.routeBundleSha256())) fail("route bundle tuple binding");
		String routeManifestSha256 = routeEntries.stream().filter(entry -> entry.path().equals("server-route-bundle/manifest.json"))
			.findFirst().orElseThrow().sha256();
		return new PinnedInputs(input, component, routeManifestSha256, inventoryBytes, componentBytes, fanInBytes, routeEntries);
	}

	// Serving admission을 수행하지 않으며 generation은 측정 요청에만 귀속된다.
	static CompiledMeasurementInputs compile(PinnedInputs pinned, long generation) {
		if (generation < 1) fail("measurement generation");
		Map<String, byte[]> selected = new LinkedHashMap<>();
		for (PinnedRouteEntry entry : pinned.routeEntries()) {
			selected.put(entry.path().substring("server-route-bundle/".length()), entry.bytes());
		}
		Map<String, byte[]> payloads = new LinkedHashMap<>();
		for (String path : List.of("payload/topology.sqlite.zst", "payload/timetable.sqlite.zst",
			"payload/accessibility.sqlite.zst", "payload/fare.sqlite.zst")) {
			payloads.put(path, selected.get(path));
		}
		RouteBundlePayloadInspection inspection = RouteBundleArtifactInspector.inspect(selected.get("manifest.json"), payloads);
		if (!inspection.manifestSha256().equals(pinned.routeManifestSha256())) fail("route manifest binding");
		RouteBundleIdentity identity = inspection.identity();
		Map<String, String> digests = Map.of("payload/topology.sqlite.zst", identity.topologySha256(),
			"payload/timetable.sqlite.zst", identity.timetableSha256(), "payload/accessibility.sqlite.zst", identity.accessibilitySha256(),
			"payload/fare.sqlite.zst", identity.fareSha256());
		var compilerInput = new RouteBundleSqliteRuntimeCompiler.Input(
			pinned.routeManifestSha256(), generation, identity.bundleId(), identity.releaseSequence(),
			identity.stationSetSha256(), digests, payloads);
		RaptorRouteBundleRuntimeView runtime = new RouteBundleSqliteRuntimeCompiler().compile(compilerInput);
		return new CompiledMeasurementInputs(runtime, identity);
	}

	static Scope scope(PinnedInputs pinned) {
		JsonNode fanIn = parse(pinned.fanInBytes(), "five-region source fan-in");
		exactKeys(fanIn, Set.of("schemaVersion", "artifactKind", "evaluatedAt", "scope", "inputs", "scopeSha256",
			"regionalMatrixSha256", "sourceSetSha256", "selectedSources", "fanInSha256"), "five-region source fan-in");
		if (!isTwo(fanIn.path("schemaVersion")) || !"current-five-region-source-fan-in".equals(text(fanIn, "artifactKind"))
			|| !pinned.measurementInput().regionalMatrixSha256().equals(sha(fanIn, "regionalMatrixSha256"))) fail("five-region source fan-in identity");
		JsonNode scope = fanIn.path("scope");
		exactKeys(scope, Set.of("targetVersion", "regionIds", "activeLineScopes", "requiredSourceDomains"), "five-region scope");
		if (!REGION_IDS.equals(orderedTexts(scope, "regionIds")) || !scope.path("activeLineScopes").isArray()
			|| !scope.path("requiredSourceDomains").isArray() || scope.path("activeLineScopes").isEmpty()
			|| scope.path("requiredSourceDomains").isEmpty()) fail("five-region scope");
		List<Line> lines = new ArrayList<>();
		for (JsonNode line : scope.path("activeLineScopes")) {
			exactKeys(line, Set.of("regionId", "operatorId", "lineId"), "five-region line");
			String region = text(line, "regionId"); if (!REGION_IDS.contains(region)) fail("five-region line");
			lines.add(new Line(region, text(line, "operatorId"), text(line, "lineId")));
		}
		List<String> lineKeys = lines.stream()
			.map(line -> line.regionId() + ':' + line.operatorId() + ':' + line.lineId() + ':').toList();
		if (lineKeys.size() != new java.util.HashSet<>(lineKeys).size()
			|| !lineKeys.equals(lineKeys.stream().sorted().toList())) fail("five-region line order");
		List<String> domains = new ArrayList<>();
		for (JsonNode domain : scope.path("requiredSourceDomains")) {
			domains.add(text(domain, "id"));
		}
		if (domains.size() != new java.util.HashSet<>(domains).size() || !domains.equals(domains.stream().sorted().toList())) fail("five-region domains");
		if (!sha256(canonical(scope).getBytes(java.nio.charset.StandardCharsets.UTF_8)).equals(sha(fanIn, "scopeSha256"))) fail("five-region scope digest");
		var payload = ((com.fasterxml.jackson.databind.node.ObjectNode) fanIn).deepCopy(); payload.remove("fanInSha256");
		if (!sha256(canonical(payload).getBytes(java.nio.charset.StandardCharsets.UTF_8)).equals(sha(fanIn, "fanInSha256"))
			|| !java.util.Arrays.equals(pinned.fanInBytes(), (canonical(fanIn) + "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8))) fail("five-region self digest");
		return new Scope(text(scope, "targetVersion"), sha(fanIn, "scopeSha256"), List.copyOf(lines));
	}

	private static MeasurementInput input(JsonNode node) {
		exactKeys(node, Set.of("schemaVersion", "artifactKind", "backendHeadSha", "dataRepository", "dataHeadSha", "dataRunId",
			"regionIds", "queryClasses", "fanIn", "routeBundleSha256", "regionalMatrixSha256", "componentSha256",
			"inventorySha256", "releaseEvidenceSha256", "releaseDecisionSha256"), "measurement input");
		if (!isOne(node.path("schemaVersion")) || !"journey-profile-predeployment-measurement-input".equals(text(node, "artifactKind"))) fail("measurement input identity");
		String backendHeadSha = gitSha(node, "backendHeadSha");
		if (!"AquilaXk/easysubway-data".equals(text(node, "dataRepository"))) fail("measurement input repository");
		String dataHeadSha = gitSha(node, "dataHeadSha");
		String dataRunId = positiveDecimal(node, "dataRunId");
		List<String> regionIds = orderedTexts(node, "regionIds");
		List<String> queryClasses = orderedTexts(node, "queryClasses");
		if (!REGION_IDS.equals(regionIds) || !QUERY_CLASSES.equals(queryClasses)) fail("measurement input scope");
		JsonNode fanIn = node.path("fanIn"); exactKeys(fanIn, Set.of("path", "sha256"), "measurement fan-in");
		return new MeasurementInput(backendHeadSha, dataHeadSha, dataRunId, regionIds, queryClasses,
			new PinnedDigest(relative(text(fanIn, "path")), sha(fanIn, "sha256")), sha(node, "routeBundleSha256"),
			sha(node, "regionalMatrixSha256"), sha(node, "componentSha256"), sha(node, "inventorySha256"),
			sha(node, "releaseEvidenceSha256"), sha(node, "releaseDecisionSha256"));
	}

	private static List<InventoryEntry> inventory(JsonNode node) {
		exactKeys(node, Set.of("schemaVersion", "artifactKind", "entries"), "candidate inventory");
		if (!isOne(node.path("schemaVersion")) || !"datapack-candidate-inventory".equals(text(node, "artifactKind")) || !node.path("entries").isArray()) fail("candidate inventory identity");
		List<InventoryEntry> entries = new ArrayList<>(); String previous = null;
		for (JsonNode entry : node.path("entries")) {
			exactKeys(entry, Set.of("path", "sizeBytes", "sha256"), "candidate inventory entry");
			String path = relative(text(entry, "path")); long size = positive(entry, "sizeBytes"); String digest = sha(entry, "sha256");
			if (previous != null && previous.compareTo(path) >= 0) fail("candidate inventory order");
			entries.add(new InventoryEntry(path, size, digest)); previous = path;
		}
		return List.copyOf(entries);
	}

	private static ComponentMetadata component(JsonNode node, MeasurementInput input, byte[] inventoryBytes) {
		exactKeys(node, Set.of("schemaVersion", "component", "repository", "gitSha", "workflowRunId", "dataVersion",
			"releaseSequence", "manifestSha256", "provenance", "artifactInventorySha256", "contractVersion", "issueRef"), "component manifest");
		if (!isOne(node.path("schemaVersion")) || !"data".equals(text(node, "component"))
			|| !"AquilaXk/easysubway-data".equals(text(node, "repository")) || !input.dataHeadSha().equals(gitSha(node, "gitSha"))
			|| !input.dataRunId().equals(decimalOrNumber(node, "workflowRunId")) || !"datapack-contract-v3".equals(text(node, "contractVersion"))
			|| !sha256(inventoryBytes).equals(sha(node, "artifactInventorySha256"))) fail("component manifest binding");
		JsonNode provenance = node.path("provenance"); exactKeys(provenance, Set.of("sourceSnapshotSetHash"), "component provenance");
		return new ComponentMetadata(sha(node, "manifestSha256"), sha(provenance, "sourceSnapshotSetHash"));
	}

	private static byte[] verified(Path root, InventoryEntry entry) throws IOException {
		byte[] bytes = regular(root, entry.path());
		if (bytes.length != entry.sizeBytes() || !sha256(bytes).equals(entry.sha256())) fail("candidate inventory digest");
		return bytes;
	}

	private static byte[] canonicalRouteEntries(List<PinnedRouteEntry> entries) {
		StringBuilder result = new StringBuilder("[");
		for (int index = 0; index < entries.size(); index += 1) {
			if (index > 0) result.append(',');
			PinnedRouteEntry entry = entries.get(index);
			result.append("{\"path\":\"").append(entry.path()).append("\",\"sha256\":\"")
				.append(entry.sha256()).append("\",\"sizeBytes\":").append(entry.sizeBytes()).append('}');
		}
		return result.append(']').toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
	}
	private static String canonical(JsonNode node) {
		if (node.isArray()) return "[" + java.util.stream.StreamSupport.stream(node.spliterator(), false).map(JourneyProfileMeasurementInputs::canonical).collect(java.util.stream.Collectors.joining(",")) + "]";
		if (!node.isObject()) return node.toString();
		return "{" + java.util.stream.StreamSupport.stream(java.util.Spliterators.spliteratorUnknownSize(node.fieldNames(), 0), false).sorted()
			.map(name -> JSON.getNodeFactory().textNode(name).toString() + ":" + canonical(node.path(name)))
			.collect(java.util.stream.Collectors.joining(",")) + "}";
	}

	private static JsonNode parse(byte[] bytes, String label) {
		try {
			JsonNode parsed = JSON.readTree(bytes);
			if (parsed == null) fail(label);
			return parsed;
		} catch (IOException exception) {
			throw new IllegalArgumentException(label + " must be strict JSON", exception);
		}
	}
	private static byte[] absoluteRegular(Path path, String label) throws IOException {
		Path normalized = path.toAbsolutePath().normalize();
		if (!Files.isRegularFile(normalized, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(normalized)) fail(label);
		return Files.readAllBytes(normalized);
	}
	private static byte[] regular(Path root, String relative) throws IOException {
		Path current = requireDirectory(root, "candidate root");
		String[] parts = relative(relative).split("/");
		for (int index = 0; index < parts.length; index += 1) {
			String part = parts[index];
			current = current.resolve(part);
			if (index == parts.length - 1) {
				if (Files.isRegularFile(current, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(current)) return Files.readAllBytes(current);
				fail("non-regular candidate input");
			}
			if (!Files.isDirectory(current, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(current)) fail("non-regular candidate input");
		}
		throw new IllegalArgumentException("non-regular candidate input");
	}
	private static Path requireDirectory(Path path, String label) throws IOException {
		Path normalized = path.toAbsolutePath().normalize();
		if (!Files.isDirectory(normalized, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(normalized)) fail(label);
		return normalized;
	}
	private static InventoryEntry exactlyOne(List<InventoryEntry> entries, String path) {
		List<InventoryEntry> matches = entries.stream().filter(entry -> entry.path().equals(path)).toList();
		if (matches.size() != 1) fail("selected candidate inventory"); return matches.getFirst();
	}
	private static String relative(String path) {
		if (path.isBlank() || path.startsWith("/") || path.contains("\\")
			|| java.util.Arrays.stream(path.split("/", -1))
				.anyMatch(part -> part.isBlank() || part.equals(".") || part.equals(".."))) {
			fail("candidate path");
		}
		return path;
	}
	private static List<String> orderedTexts(JsonNode node, String field) {
		JsonNode values = node.path(field);
		if (!values.isArray()) fail(field);
		List<String> result = new ArrayList<>();
		for (JsonNode value : values) result.add(text(value));
		if (result.isEmpty() || Set.copyOf(result).size() != result.size()) fail(field);
		return List.copyOf(result);
	}
	private static void exactKeys(JsonNode node, Set<String> expected, String label) {
		if (!node.isObject() || node.size() != expected.size()
			|| !node.properties().stream().map(java.util.Map.Entry::getKey)
				.collect(java.util.stream.Collectors.toSet()).equals(expected)) fail(label);
	}
	private static String text(JsonNode node, String field) { return text(node.path(field)); }
	private static String text(JsonNode node) { if (!node.isTextual() || node.textValue().isBlank()) fail("text"); return node.textValue(); }
	private static boolean isOne(JsonNode node) { return node.isIntegralNumber() && node.canConvertToInt() && node.intValue() == 1; }
	private static boolean isTwo(JsonNode node) { return node.isIntegralNumber() && node.canConvertToInt() && node.intValue() == 2; }
	private static long positive(JsonNode node, String field) {
		JsonNode value = node.path(field);
		if (!value.isIntegralNumber() || !value.canConvertToLong()
			|| value.longValue() < 1 || value.longValue() > 9_007_199_254_740_991L) fail(field);
		return value.longValue();
	}
	private static String positiveDecimal(JsonNode node, String field) { String value = text(node, field); if (!value.matches("[1-9][0-9]*")) fail(field); return value; }
	private static String decimalOrNumber(JsonNode node, String field) {
		JsonNode value = node.path(field);
		if (value.isTextual()) return positiveDecimal(node, field);
		return Long.toString(positive(node, field));
	}
	private static String sha(JsonNode node, String field) { String value = text(node, field); if (!value.matches("[a-f0-9]{64}")) fail(field); return value; }
	private static String gitSha(JsonNode node, String field) { String value = text(node, field); if (!value.matches("[a-f0-9]{40}")) fail(field); return value; }
	private static String sha256(byte[] bytes) { try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); } catch (NoSuchAlgorithmException exception) { throw new IllegalStateException(exception); } }
	private static void fail(String label) { throw new IllegalArgumentException("invalid journey profile measurement input: " + label); }

	record MeasurementInput(
		String backendHeadSha, String dataHeadSha, String dataRunId,
		List<String> regionIds, List<String> queryClasses, PinnedDigest fanIn,
		String routeBundleSha256, String regionalMatrixSha256, String componentSha256,
		String inventorySha256, String releaseEvidenceSha256, String releaseDecisionSha256
	) { }
	record PinnedDigest(String path, String sha256) { }
	record ComponentMetadata(String manifestSha256, String sourceSnapshotSetHash) { }
	record InventoryEntry(String path, long sizeBytes, String sha256) { }
	record PinnedRouteEntry(String path, long sizeBytes, String sha256, byte[] bytes) {
		public PinnedRouteEntry { bytes = bytes.clone(); }
		@Override public byte[] bytes() { return bytes.clone(); }
	}
	record PinnedInputs(
		MeasurementInput measurementInput, ComponentMetadata componentMetadata,
		String routeManifestSha256, byte[] inventoryBytes, byte[] componentManifestBytes,
		byte[] fanInBytes, List<PinnedRouteEntry> routeEntries
	) {
		public PinnedInputs {
			inventoryBytes = inventoryBytes.clone();
			componentManifestBytes = componentManifestBytes.clone();
			fanInBytes = fanInBytes.clone();
			routeEntries = List.copyOf(routeEntries);
		}
		@Override public byte[] inventoryBytes() { return inventoryBytes.clone(); }
		@Override public byte[] componentManifestBytes() { return componentManifestBytes.clone(); }
		@Override public byte[] fanInBytes() { return fanInBytes.clone(); }
	}
	record CompiledMeasurementInputs(RaptorRouteBundleRuntimeView runtime, RouteBundleIdentity identity) { }
	record Scope(String targetVersion, String scopeSha256, List<Line> activeLines) { public Scope { activeLines = List.copyOf(activeLines); } }
	record Line(String regionId, String operatorId, String lineId) { }
}
