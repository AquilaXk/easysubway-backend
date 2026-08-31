package com.easysubway.journey.benchmark;

import static java.util.Objects.requireNonNull;

import com.easysubway.journey.application.JourneyExecutionFailure;
import com.easysubway.journey.application.JourneyExecutionResult;
import com.easysubway.journey.application.JourneyRequest;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/** Strict, FINAL-bound nationwide benchmark corpus contract for #297. */
final class JourneyV3FinalCoverageBenchmarkCorpus {

	private static final ObjectMapper JSON = new ObjectMapper(JsonFactory.builder()
		.enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
		.enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
	private static final Pattern SHA_256 = Pattern.compile("[0-9a-f]{64}");
	private static final Set<String> ROOT_FIELDS = Set.of("schemaVersion", "corpusVersion", "finalBinding",
		"regions", "operators", "requiredCoverageCells", "cases");
	private static final Set<String> FINAL_BINDING_FIELDS = Set.of("descriptorSha256", "finalSha256",
		"finalRawSha256", "publicationReceiptSha256", "publicationReceiptRawSha256", "stationSetSha256",
		"sourceSnapshotSetHash", "topologySha256", "accessibilitySha256");
	private static final Set<String> COVERAGE_CELL_FIELDS = Set.of("regionId", "operatorId", "transferBucket",
		"timeBand", "serviceDay", "mobilityProfile", "constraintMode");
	private static final Set<String> CASE_FIELDS = Set.of("id", "originStationId", "destinationStationId",
		"departureLocalTime", "regionId", "operatorId", "transferBucket", "timeBand", "serviceDay",
		"mobilityProfile", "constraintMode", "expectedOutcome");

	private JourneyV3FinalCoverageBenchmarkCorpus() {
	}

	static Corpus parse(String rawCorpus) {
		requireNonNull(rawCorpus, "rawCorpus");
		try {
			JsonNode root = JSON.readTree(rawCorpus);
			requireExactFields(root, ROOT_FIELDS, "corpus");
			if (!root.path("schemaVersion").isIntegralNumber() || root.path("schemaVersion").intValue() != 2
				|| !"v2".equals(requiredText(root, "corpusVersion", "corpus"))) {
				throw invalid("corpus version is invalid");
			}
			FinalBinding binding = parseFinalBinding(root.path("finalBinding"));
			Set<String> regions = parseDeclaredSet(root.path("regions"), "regions");
			Set<String> operators = parseDeclaredSet(root.path("operators"), "operators");
			Set<CoverageCell> requiredCells = parseCoverageCells(root.path("requiredCoverageCells"));
			List<Case> cases = parseCases(root.path("cases"));
			validateProjection(regions, operators, requiredCells, cases);
			return new Corpus(sha256(rawCorpus.getBytes(StandardCharsets.UTF_8)), binding, regions, operators,
				requiredCells, cases);
		} catch (IOException exception) {
			throw new IllegalArgumentException("corpus is malformed", exception);
		}
	}

	static void validateObservedOutcome(Case benchmarkCase, JourneyExecutionResult observed) {
		requireNonNull(benchmarkCase, "benchmarkCase");
		requireNonNull(observed, "observed");
		ExpectedOutcome expected = benchmarkCase.expectedOutcome();
		if (expected instanceof ExpectedOutcome.Success) {
			if (!(observed instanceof JourneyExecutionResult.Success success)) {
				throw invalid("benchmark outcome does not match expected success");
			}
			if (success.journeys().size() != 1) {
				throw invalid("benchmark success must contain exactly one journey");
			}
			TransferBucket actual = transferBucket(success.journeys().getFirst().transferCount());
			if (actual != benchmarkCase.transferBucket()) {
				throw invalid("benchmark transfer bucket does not match observed journey");
			}
			return;
		}
		if (!(observed instanceof JourneyExecutionFailure failure)) {
			throw invalid("benchmark outcome does not match expected failure");
		}
		ExpectedOutcome.Failure expectedFailure = (ExpectedOutcome.Failure) expected;
		if (failure.reason() != expectedFailure.reason()) {
			throw invalid("benchmark failure reason does not match observed result");
		}
	}

	static TransferBucket transferBucket(int transferCount) {
		return switch (transferCount) {
			case 0 -> TransferBucket.DIRECT;
			case 1 -> TransferBucket.ONE;
			case 2 -> TransferBucket.TWO;
			case 3 -> TransferBucket.THREE;
			default -> throw invalid("observed transfer count is outside the corpus contract");
		};
	}

	private static FinalBinding parseFinalBinding(JsonNode node) {
		requireExactFields(node, FINAL_BINDING_FIELDS, "final binding");
		return new FinalBinding(requiredSha(node, "descriptorSha256"), requiredSha(node, "finalSha256"),
			requiredSha(node, "finalRawSha256"), requiredSha(node, "publicationReceiptSha256"),
			requiredSha(node, "publicationReceiptRawSha256"), requiredSha(node, "stationSetSha256"),
			requiredSha(node, "sourceSnapshotSetHash"), requiredSha(node, "topologySha256"),
			requiredSha(node, "accessibilitySha256"));
	}

	private static Set<String> parseDeclaredSet(JsonNode node, String name) {
		if (!node.isArray() || node.isEmpty()) throw invalid(name + " must be a nonempty array");
		var values = new LinkedHashSet<String>();
		for (JsonNode item : node) {
			if (!item.isTextual() || item.textValue().isBlank() || !values.add(item.textValue())) {
				throw invalid(name + " must contain unique nonblank text values");
			}
		}
		return Set.copyOf(values);
	}

	private static Set<CoverageCell> parseCoverageCells(JsonNode node) {
		if (!node.isArray() || node.isEmpty()) throw invalid("required coverage cells must be a nonempty array");
		var values = new LinkedHashSet<CoverageCell>();
		for (JsonNode item : node) {
			CoverageCell cell = parseCoverageCell(item, "required coverage cell");
			if (!values.add(cell)) throw invalid("required coverage cells must be unique");
		}
		return Set.copyOf(values);
	}

	private static List<Case> parseCases(JsonNode node) {
		if (!node.isArray() || node.isEmpty()) throw invalid("cases must be a nonempty array");
		var values = new ArrayList<Case>();
		var ids = new HashSet<String>();
		var cells = new HashSet<CoverageCell>();
		for (JsonNode item : node) {
			requireExactFields(item, CASE_FIELDS, "case");
			String id = requiredText(item, "id", "case");
			String originStationId = requiredText(item, "originStationId", "case");
			String destinationStationId = requiredText(item, "destinationStationId", "case");
			if (originStationId.equals(destinationStationId)) throw invalid("case origin and destination must differ");
			String departure = requiredText(item, "departureLocalTime", "case");
			validateTime(departure);
			CoverageCell cell = parseCoverageCell(item, "case");
			Case value = new Case(id, originStationId, destinationStationId, departure, cell.regionId(), cell.operatorId(),
				cell.transferBucket(), cell.timeBand(), cell.serviceDay(), cell.mobilityProfile(), cell.constraintMode(),
				parseExpectedOutcome(item.path("expectedOutcome")));
			if (!ids.add(id) || !cells.add(value.coverageCell())) throw invalid("case identifiers and cells must be unique");
			values.add(value);
		}
		return List.copyOf(values);
	}

	private static CoverageCell parseCoverageCell(JsonNode node, String label) {
		requireExactFields(node, label.equals("case") ? CASE_FIELDS : COVERAGE_CELL_FIELDS, label);
		return new CoverageCell(requiredText(node, "regionId", label), requiredText(node, "operatorId", label),
			requiredEnum(node, "transferBucket", TransferBucket.class, label), requiredText(node, "timeBand", label),
			requiredEnum(node, "serviceDay", ServiceDay.class, label),
			requiredEnum(node, "mobilityProfile", JourneyRequest.MobilityProfile.class, label),
			requiredEnum(node, "constraintMode", JourneyRequest.ConstraintMode.class, label));
	}

	private static ExpectedOutcome parseExpectedOutcome(JsonNode node) {
		if (node == null || !node.isObject() || !node.path("kind").isTextual()) {
			throw invalid("expected outcome is invalid");
		}
		return switch (node.path("kind").textValue()) {
			case "SUCCESS" -> {
				requireExactFields(node, Set.of("kind"), "success expected outcome");
				yield new ExpectedOutcome.Success();
			}
			case "FAILURE" -> {
				requireExactFields(node, Set.of("kind", "reason"), "failure expected outcome");
				JourneyExecutionFailure.Reason reason = requiredEnum(
					node, "reason", JourneyExecutionFailure.Reason.class, "failure expected outcome");
				if (reason != JourneyExecutionFailure.Reason.NO_ROUTE) {
					throw invalid("only NO_ROUTE is an observable expected failure");
				}
				yield new ExpectedOutcome.Failure(reason);
			}
			default -> throw invalid("expected outcome kind is invalid");
		};
	}

	private static void validateProjection(Set<String> regions, Set<String> operators, Set<CoverageCell> requiredCells,
		List<Case> cases) {
		Set<String> usedRegions = new HashSet<>();
		Set<String> usedOperators = new HashSet<>();
		Set<CoverageCell> projectedCells = new HashSet<>();
		for (Case benchmarkCase : cases) {
			usedRegions.add(benchmarkCase.regionId());
			usedOperators.add(benchmarkCase.operatorId());
			projectedCells.add(benchmarkCase.coverageCell());
		}
		if (!regions.equals(usedRegions) || !operators.equals(usedOperators) || !requiredCells.equals(projectedCells)) {
			throw invalid("declared coverage does not exactly match the case projection");
		}
	}

	private static void validateTime(String value) {
		try {
			LocalTime parsed = LocalTime.parse(value);
			if (!value.matches("[0-2]\\d:[0-5]\\d") || value.length() != 5 || parsed.getHour() > 23
				|| parsed.getSecond() != 0 || parsed.getNano() != 0) {
				throw invalid("case departureLocalTime is invalid");
			}
		} catch (RuntimeException exception) {
			if (exception instanceof IllegalArgumentException) throw exception;
			throw invalid("case departureLocalTime is invalid");
		}
	}

	private static void requireExactFields(JsonNode node, Set<String> expected, String label) {
		if (node == null || !node.isObject() || node.size() != expected.size()) throw invalid(label + " fields are invalid");
		var actual = new HashSet<String>();
		node.fieldNames().forEachRemaining(actual::add);
		if (!actual.equals(expected)) throw invalid(label + " fields are invalid");
	}

	private static String requiredText(JsonNode node, String field, String label) {
		JsonNode value = node.path(field);
		if (!value.isTextual() || value.textValue().isBlank()) throw invalid(label + " " + field + " is required");
		return value.textValue();
	}

	private static String requiredSha(JsonNode node, String field) {
		String value = requiredText(node, field, "final binding");
		if (!SHA_256.matcher(value).matches()) throw invalid("final binding " + field + " must be lowercase SHA-256");
		return value;
	}

	private static <T extends Enum<T>> T requiredEnum(JsonNode node, String field, Class<T> type, String label) {
		String value = requiredText(node, field, label);
		try {
			return Enum.valueOf(type, value);
		} catch (IllegalArgumentException exception) {
			throw invalid(label + " " + field + " is invalid");
		}
	}

	private static String sha256(byte[] bytes) {
		try {
			return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
		} catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException(exception);
		}
	}

	private static IllegalArgumentException invalid(String message) {
		return new IllegalArgumentException(message);
	}

	enum TransferBucket { DIRECT, ONE, TWO, THREE }
	enum ServiceDay { WEEKDAY, WEEKEND }

	record Corpus(String rawSha256, FinalBinding finalBinding, Set<String> regions, Set<String> operators,
		Set<CoverageCell> requiredCoverageCells, List<Case> cases) {
		Corpus {
			requireNonNull(rawSha256, "rawSha256");
			finalBinding = requireNonNull(finalBinding, "finalBinding");
			regions = Set.copyOf(regions);
			operators = Set.copyOf(operators);
			requiredCoverageCells = Set.copyOf(requiredCoverageCells);
			cases = List.copyOf(cases);
		}
	}

	record FinalBinding(String descriptorSha256, String finalSha256, String finalRawSha256,
		String publicationReceiptSha256, String publicationReceiptRawSha256, String stationSetSha256,
		String sourceSnapshotSetHash, String topologySha256, String accessibilitySha256) { }

	record CoverageCell(String regionId, String operatorId, TransferBucket transferBucket, String timeBand,
		ServiceDay serviceDay, JourneyRequest.MobilityProfile mobilityProfile,
		JourneyRequest.ConstraintMode constraintMode) { }

	record Case(String id, String originStationId, String destinationStationId, String departureLocalTime,
		String regionId, String operatorId, TransferBucket transferBucket, String timeBand, ServiceDay serviceDay,
		JourneyRequest.MobilityProfile mobilityProfile, JourneyRequest.ConstraintMode constraintMode,
		ExpectedOutcome expectedOutcome) {
		CoverageCell coverageCell() {
			return new CoverageCell(regionId, operatorId, transferBucket, timeBand, serviceDay, mobilityProfile,
				constraintMode);
		}
	}

	sealed interface ExpectedOutcome permits ExpectedOutcome.Success, ExpectedOutcome.Failure {
		record Success() implements ExpectedOutcome { }
		record Failure(JourneyExecutionFailure.Reason reason) implements ExpectedOutcome { }
	}
}
