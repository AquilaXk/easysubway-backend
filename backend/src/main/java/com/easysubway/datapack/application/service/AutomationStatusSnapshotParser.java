package com.easysubway.datapack.application.service;

import com.easysubway.datapack.domain.AutomationStatusSnapshot;
import com.easysubway.datapack.domain.AutomationStatusSnapshot.ActiveDatapack;
import com.easysubway.datapack.domain.AutomationStatusSnapshot.BehindCap;
import com.easysubway.datapack.domain.AutomationStatusSnapshot.FailureIssue;
import com.easysubway.datapack.domain.AutomationStatusSnapshot.RunSummary;
import com.easysubway.datapack.domain.AutomationStatusSnapshot.StaleClaim;
import com.easysubway.datapack.domain.AutomationStatusSnapshot.Stage;
import com.easysubway.datapack.domain.AutomationStatusSnapshot.Stuck;
import com.easysubway.datapack.domain.AutomationStatusSnapshot.StuckPull;
import com.easysubway.datapack.domain.InvalidAutomationStatusException;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * data 레포가 게시하는 자동화 상태 snapshot(v1)을 엄격하게 읽는다. 알려지지 않은 필드·중복 키·잘못된 형식·github.com 밖의 링크·
 * 과대 목록은 모두 거부한다(외부 입력이므로 화면에 그대로 렌더되기 전에 닫는다).
 */
@Component
public class AutomationStatusSnapshotParser {

	static final int MAX_STAGES = 20;
	static final int MAX_LIST = 50;
	private static final int MAX_TEXT = 200;
	private static final int MAX_TITLE = 300;
	private static final Pattern STAGE_ID = Pattern.compile("[a-z][a-z0-9-]{0,31}");
	private static final Pattern GITHUB_URL = Pattern.compile(
		"https://github\\.com/AquilaXk/[A-Za-z0-9._-]+/(?:actions/runs|issues|pull)/[1-9][0-9]{0,17}");
	private static final Set<String> RUN_STATUSES = Set.of("queued", "in_progress", "completed", "waiting", "pending", "requested");
	private static final Set<String> RUN_CONCLUSIONS = Set.of(
		"success", "failure", "cancelled", "skipped", "timed_out", "action_required", "neutral", "stale", "startup_failure");
	private static final Set<String> STUCK_REASONS = Set.of("BEHIND", "OLD");

	private final ObjectMapper mapper = JsonMapper.builder()
		.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
		.enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
		.build();

	public AutomationStatusSnapshot parse(String json) {
		if (json == null || json.isBlank()) {
			throw invalid("본문이 비어 있습니다");
		}
		JsonNode root;
		try {
			root = mapper.readTree(json);
		} catch (JsonProcessingException failure) {
			throw invalid("JSON 형식이 아닙니다");
		}
		object(root, Set.of("schemaVersion", "artifactKind", "generatedAt", "activeDatapack", "stages", "failureIssues", "stuck", "candidateInFlight"), "snapshot");
		if (!root.get("schemaVersion").isIntegralNumber() || root.get("schemaVersion").asInt() != 1) {
			throw invalid("지원하지 않는 schemaVersion입니다");
		}
		if (!"automation-status-snapshot".equals(text(root, "artifactKind", MAX_TEXT))) {
			throw invalid("artifactKind가 맞지 않습니다");
		}
		return new AutomationStatusSnapshot(
			instant(root, "generatedAt"),
			activeDatapack(root.get("activeDatapack")),
			stages(root),
			failureIssues(root),
			stuck(root.get("stuck")),
			bool(root, "candidateInFlight"));
	}

	private ActiveDatapack activeDatapack(JsonNode node) {
		object(node, Set.of("releaseSequence", "publishedAt", "expiresAt"), "activeDatapack");
		return new ActiveDatapack(positiveLong(node, "releaseSequence"), instant(node, "publishedAt"), instant(node, "expiresAt"));
	}

	private List<Stage> stages(JsonNode root) {
		List<Stage> stages = new ArrayList<>();
		for (JsonNode item : array(root, "stages", MAX_STAGES)) {
			object(item, Set.of("id", "label", "latest", "lastSuccessAt", "inFlight"), "stage");
			String id = text(item, "id", 32);
			if (!STAGE_ID.matcher(id).matches()) {
				throw invalid("stage id 형식이 맞지 않습니다");
			}
			stages.add(new Stage(id, text(item, "label", MAX_TEXT), run(item.get("latest")),
				nullableInstant(item, "lastSuccessAt"), bool(item, "inFlight")));
		}
		return List.copyOf(stages);
	}

	private RunSummary run(JsonNode node) {
		if (node.isNull()) {
			return null;
		}
		object(node, Set.of("runId", "url", "status", "conclusion", "createdAt", "updatedAt"), "run");
		String status = text(node, "status", 32);
		if (!RUN_STATUSES.contains(status)) {
			throw invalid("run status 값이 맞지 않습니다");
		}
		String conclusion = null;
		if (!node.get("conclusion").isNull()) {
			conclusion = text(node, "conclusion", 32);
			if (!RUN_CONCLUSIONS.contains(conclusion)) {
				throw invalid("run conclusion 값이 맞지 않습니다");
			}
		}
		return new RunSummary(positiveLong(node, "runId"), githubUrl(node, "url"), status, conclusion,
			instant(node, "createdAt"), instant(node, "updatedAt"));
	}

	private List<FailureIssue> failureIssues(JsonNode root) {
		List<FailureIssue> issues = new ArrayList<>();
		for (JsonNode item : array(root, "failureIssues", MAX_LIST)) {
			object(item, Set.of("number", "title", "url", "createdAt"), "failureIssue");
			issues.add(new FailureIssue(positiveLong(item, "number"), text(item, "title", MAX_TITLE),
				githubUrl(item, "url"), instant(item, "createdAt")));
		}
		return List.copyOf(issues);
	}

	private Stuck stuck(JsonNode node) {
		object(node, Set.of("pulls", "claims", "behindCap"), "stuck");
		List<StuckPull> pulls = new ArrayList<>();
		for (JsonNode item : array(node, "pulls", MAX_LIST)) {
			object(item, Set.of("number", "title", "url", "branch", "stage", "createdAt", "reason"), "stuckPull");
			String reason = text(item, "reason", 16);
			if (!STUCK_REASONS.contains(reason)) {
				throw invalid("막힌 PR 사유 값이 맞지 않습니다");
			}
			pulls.add(new StuckPull(positiveLong(item, "number"), text(item, "title", MAX_TITLE), githubUrl(item, "url"),
				text(item, "branch", MAX_TEXT), text(item, "stage", 64), instant(item, "createdAt"), reason));
		}
		List<StaleClaim> claims = new ArrayList<>();
		for (JsonNode item : array(node, "claims", MAX_LIST)) {
			object(item, Set.of("branch", "committedAt"), "staleClaim");
			claims.add(new StaleClaim(text(item, "branch", MAX_TEXT), instant(item, "committedAt")));
		}
		List<BehindCap> behindCap = new ArrayList<>();
		for (JsonNode item : array(node, "behindCap", MAX_LIST)) {
			object(item, Set.of("stage", "number", "closures"), "behindCap");
			JsonNode closures = item.get("closures");
			if (!closures.isIntegralNumber() || closures.asInt() < 0) {
				throw invalid("closures 값이 맞지 않습니다");
			}
			behindCap.add(new BehindCap(text(item, "stage", 64), positiveLong(item, "number"), closures.asInt()));
		}
		return new Stuck(List.copyOf(pulls), List.copyOf(claims), List.copyOf(behindCap));
	}

	// ---------- 필드 검증 도구 ----------

	private static void object(JsonNode node, Set<String> keys, String label) {
		if (node == null || !node.isObject()) {
			throw invalid(label + "는 객체여야 합니다");
		}
		Iterator<String> names = node.fieldNames();
		int count = 0;
		while (names.hasNext()) {
			if (!keys.contains(names.next())) {
				throw invalid(label + "에 알 수 없는 필드가 있습니다");
			}
			count++;
		}
		if (count != keys.size()) {
			throw invalid(label + "의 필드가 모자랍니다");
		}
	}

	private static List<JsonNode> array(JsonNode owner, String key, int max) {
		JsonNode node = owner.get(key);
		if (node == null || !node.isArray()) {
			throw invalid(key + "는 배열이어야 합니다");
		}
		if (node.size() > max) {
			throw invalid(key + " 항목이 너무 많습니다");
		}
		List<JsonNode> items = new ArrayList<>();
		node.forEach(items::add);
		return items;
	}

	private static String text(JsonNode node, String key, int max) {
		JsonNode value = node.get(key);
		if (value == null || !value.isTextual()) {
			throw invalid(key + "는 문자열이어야 합니다");
		}
		String text = value.asText();
		if (text.isEmpty() || text.length() > max || text.chars().anyMatch(AutomationStatusSnapshotParser::isHiddenOrControl)) {
			throw invalid(key + " 값이 맞지 않습니다");
		}
		return text;
	}

	/** 제어 문자에 더해 양방향 제어·서식 문자(U+202E 등)와 줄·문단 구분 문자도 거부한다: 공개 저장소의 제목이 화면 라벨 순서를 바꾸지 못하게 한다. */
	private static boolean isHiddenOrControl(int codePoint) {
		int type = Character.getType(codePoint);
		return Character.isISOControl(codePoint) || type == Character.FORMAT || type == Character.LINE_SEPARATOR || type == Character.PARAGRAPH_SEPARATOR;
	}

	private static String githubUrl(JsonNode node, String key) {
		String url = text(node, key, MAX_TITLE);
		if (!GITHUB_URL.matcher(url).matches()) {
			throw invalid(key + "는 이 조직의 github.com 링크여야 합니다");
		}
		return url;
	}

	private static Instant instant(JsonNode node, String key) {
		try {
			return Instant.parse(text(node, key, 40));
		} catch (DateTimeParseException failure) {
			throw invalid(key + "는 UTC 시각이어야 합니다");
		}
	}

	private static Instant nullableInstant(JsonNode node, String key) {
		JsonNode value = node.get(key);
		return value != null && value.isNull() ? null : instant(node, key);
	}

	private static boolean bool(JsonNode node, String key) {
		JsonNode value = node.get(key);
		if (value == null || !value.isBoolean()) {
			throw invalid(key + "는 true/false여야 합니다");
		}
		return value.asBoolean();
	}

	private static long positiveLong(JsonNode node, String key) {
		JsonNode value = node.get(key);
		if (value == null || !value.isIntegralNumber() || !value.canConvertToLong() || value.asLong() < 1) {
			throw invalid(key + "는 양의 정수여야 합니다");
		}
		return value.asLong();
	}

	private static InvalidAutomationStatusException invalid(String message) {
		return new InvalidAutomationStatusException(message);
	}
}
