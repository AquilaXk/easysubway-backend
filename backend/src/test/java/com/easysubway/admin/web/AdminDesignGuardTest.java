package com.easysubway.admin.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("관리자 디자인 가드")
class AdminDesignGuardTest {

	private static final Path ROOT = Path.of("..").toAbsolutePath().normalize();

	// V6-04(#2276): admin-v3.css는 4책임 파일의 import manifest이므로 선언은 아래 4개 파일이 소유한다.
	private static final String CSS_TOKENS = "backend/src/main/resources/static/css/admin-tokens.css";
	private static final String CSS_FOUNDATION = "backend/src/main/resources/static/css/admin-foundation.css";
	private static final String CSS_SHELL = "backend/src/main/resources/static/css/admin-shell.css";
	private static final String CSS_COMPONENTS = "backend/src/main/resources/static/css/admin-components.css";
	private static final String CSS_DATA = "backend/src/main/resources/static/css/admin-data.css";
	private static final String CSS_ADMIN_V3 = "backend/src/main/resources/static/css/admin-v3.css";
	private static final String CSS_OPERATOR = "backend/src/main/resources/static/css/operator-v3.css";
	private static final String JS_DASHBOARD_CHARTS = "backend/src/main/resources/static/js/admin/dashboard-charts.js";
	private static final String JS_BATCH_HISTORY_CHARTS = "backend/src/main/resources/static/js/admin/batch-history-charts.js";
	private static final String JS_OPERATOR_REPORT_CHARTS = "backend/src/main/resources/static/js/operator/report-charts.js";
	private static final String COLOR_SYSTEM_JSON = "tools/design/easysubway-color-system.json";
	private static final Pattern CSS_CUSTOM_PROPERTY = Pattern.compile(
		"(?m)^\\s*(--[a-z0-9-]+)\\s*:\\s*([^;]+);");
	private static final Pattern HEX_COLOR = Pattern.compile("#[0-9a-fA-F]{3,8}\\b");
	private static final List<String> CHART_JS_FILES = List.of(
		JS_DASHBOARD_CHARTS, JS_BATCH_HISTORY_CHARTS, JS_OPERATOR_REPORT_CHARTS);

	// import 순서(tokens → foundation → shell → components → data)를 그대로 소유하는 4책임 파일.
	private static final List<String> RESPONSIBILITY_FILES = List.of(
		CSS_FOUNDATION, CSS_SHELL, CSS_COMPONENTS, CSS_DATA);

	// 색·shadow·radius 스캔 대상: token 레이어 + 4책임 레이어 + operator(공유 token consumer).
	private static final List<String> CSS_FILES = List.of(
		CSS_TOKENS, CSS_FOUNDATION, CSS_SHELL, CSS_COMPONENTS, CSS_DATA, CSS_OPERATOR);

	// 초록/청록 계열(및 이전 무채색·상태 리터럴) 하드코딩 금지 목록. 대소문자 무시.
	private static final Pattern FORBIDDEN_HEX = Pattern.compile(
		"#(?:0f6b52|4fcfa9|123a37|0c2b28|1f6b45|eef4f1|0f2730|16302f|5b6f6d|8a9b98"
			+ "|8a5a00|b3402c|f5f6f6|e4e8e7|d3dbd9|f6f8f7|2a2f31|1a1d1e|c8d3dc|8a9aa0"
			+ "|eef1f2|1f6f6a|1f7a44|e4f4ea|2456b3|eef4ff|5f6b7a|384250|eef1f5|7c949a)\\b",
		Pattern.CASE_INSENSITIVE);

	// rgba()/hsl() 함수로 우회하는 금지 RGB 값 검사. FORBIDDEN_HEX의 각 hex를 RGB 십진수로 변환한 목록.
	private static final Pattern RGB_FUNC = Pattern.compile(
		"rgba?\\s*\\(\\s*(\\d+)\\s*,\\s*(\\d+)\\s*,\\s*(\\d+)", Pattern.CASE_INSENSITIVE);
	private static final Set<String> FORBIDDEN_RGB = Set.of(
		"15,107,82", "79,207,169", "18,58,55", "12,43,40", "31,107,69", "238,244,241",
		"15,39,48", "22,48,47", "91,111,109", "138,155,152", "138,90,0", "179,64,44",
		"245,246,246", "228,232,231", "211,219,217", "246,248,247", "42,47,49", "26,29,30",
		"200,211,220", "138,154,160", "238,241,242", "31,111,106", "31,122,68", "228,244,234",
		"36,86,179", "238,244,255", "95,107,122", "56,66,80", "238,241,245", "124,148,154");

	// box-shadow는 none 또는 var(--admin-shadow-card) 만 허용, 그 외 raw 값 금지.
	private static final Pattern BOX_SHADOW = Pattern.compile(
		"box-shadow\\s*:\\s*([^;]+)", Pattern.CASE_INSENSITIVE);
	private static final Pattern BOX_SHADOW_ALLOWED = Pattern.compile(
		"^(none\\b|var\\(--admin-shadow-card\\))", Pattern.CASE_INSENSITIVE);

	// border-radius 리터럴 px 값. 9px 이상은 금지(단 999px 이상 캡슐은 예외).
	private static final Pattern BORDER_RADIUS_PX = Pattern.compile(
		"border-radius\\s*:\\s*([^;]*)", Pattern.CASE_INSENSITIVE);
	private static final Pattern PX_LITERAL = Pattern.compile("(\\d+)px");
	private static final Pattern TABLE_TAG = Pattern.compile("<table\\b");
	// kv-table 유틸리티(#2349 PR⑩c)는 620px 스캔 폭 하한을 풀어(min-width: 0) 가로로 넘치지 않으므로
	// 접근성 scroll wrapper 계약(#2071) 대상에서 제외한다.
	private static final Pattern KV_TABLE_TAG = Pattern.compile("<table class=\"admin-kv-table\"");
	private static final Pattern CONDITIONAL_TABLE = Pattern.compile("<table\\b[^>]*\\bth:if=");
	private static final Pattern ACCESSIBLE_TABLE_WRAPPER = Pattern.compile(
		"<div class=\"admin-table-scroll\" tabindex=\"0\" role=\"group\"\\s+"
			+ "aria-label=\"가로로 스크롤 가능한 [^\"]*표\"[^>]*>\\s*<table\\b",
		Pattern.DOTALL);
	private static final Pattern TABLE_WRAPPER_CLOSE = Pattern.compile("</table>\\s*</div>");

	// #2425(R1·R11): 데이터팩·공통코드 표 헤더에 남는 영문 필드명 원문 금지 목록. 정확히 일치(trim, 소문자)할 때만
	// 위반이다 — "provider entity"처럼 여러 단어로 합쳐지거나 한국어가 섞이면(예: "raw sha256", "증거 해시") 잡지 않는다.
	// strict는 계획상 열 헤더에 그대로 남기는 예외 토큰이라 이 목록에서 제외한다. "ID"(대문자)는 별도로 허용한다.
	private static final Set<String> FORBIDDEN_TABLE_HEADER_TOKENS = Set.of(
		"id", "entity", "field", "before", "after", "station", "line", "facility", "evidence",
		"command", "source", "status", "channel", "current", "scope", "version", "group", "code",
		"reason", "requested", "approved", "conflict", "window", "superseded", "production",
		"installation", "operation", "meaning", "verified", "freshness", "confidence", "override",
		"snapshot", "method", "manifest", "provider", "rows", "edge", "type", "verification",
		"provenance", "generated", "hash", "gates", "rollback", "approval", "updated", "diff");
	private static final Pattern STATIC_TH = Pattern.compile("<th\\b([^>]*)>([^<]*)</th>", Pattern.DOTALL);
	private static final Pattern H2_TAG = Pattern.compile("<h2\\b[^>]*>([^<]*)</h2>", Pattern.DOTALL);

	@Test
	@DisplayName("색상 JSON version 1의 primitive·semantic property와 관리자 alias가 정확히 일치한다")
	void colorSystemJsonMatchesAdminTokensAndCompatibilityAliases() throws IOException {
		JsonNode colorSystem = new ObjectMapper().readTree(read(COLOR_SYSTEM_JSON));
		assertThat(colorSystem.path("version").asInt()).isEqualTo(1);

		Map<String, String> expectedProperties = new LinkedHashMap<>();
		JsonNode primitives = colorSystem.path("primitives");
		assertThat(primitives.size()).as("JSON primitive 수").isEqualTo(25);
		primitives.properties().forEach(entry ->
			expectedProperties.put(primitiveProperty(entry.getKey()), entry.getValue().asText()));

		JsonNode semantic = colorSystem.path("semantic");
		assertThat(semantic.size()).as("JSON semantic 수").isEqualTo(31);
		semantic.properties().forEach(entry -> expectedProperties.put(
			primitiveProperty(entry.getKey()), "var(" + primitiveProperty(entry.getValue().asText()) + ")"));

		String tokensCss = read(CSS_TOKENS).replaceAll("(?s)/\\*.*?\\*/", "");
		Matcher root = Pattern.compile("(?m)^\\s*:root\\s*\\{([^}]*)}", Pattern.DOTALL).matcher(tokensCss);
		assertThat(root.find()).as(":root token block이 존재한다").isTrue();
		Map<String, String> cssProperties = cssCustomProperties(root.group(1));
		assertThat(root.find()).as(":root token block은 하나다").isFalse();
		Map<String, String> esProperties = new LinkedHashMap<>();
		cssProperties.forEach((name, value) -> {
			if (name.startsWith("--es-")) {
				esProperties.put(name, value);
			}
		});
		assertThat(esProperties).containsExactlyInAnyOrderEntriesOf(expectedProperties);

		Map<String, String> expectedAliases = Map.ofEntries(
			Map.entry("--admin-bg", "var(--es-surface-scaffold)"),
			Map.entry("--admin-surface", "var(--es-surface-default)"),
			Map.entry("--admin-border", "var(--es-border-subtle)"),
			Map.entry("--admin-border-strong", "var(--es-interaction-secondary-border)"),
			Map.entry("--admin-header-bg", "var(--es-surface-brand-chrome)"),
			Map.entry("--admin-ink", "var(--es-content-primary)"),
			Map.entry("--admin-ink-2", "var(--es-content-secondary)"),
			Map.entry("--admin-ink-3", "var(--es-content-muted)"),
			Map.entry("--admin-accent", "var(--es-interaction-primary)"),
			Map.entry("--admin-accent-soft", "var(--es-interaction-secondary-surface)"),
			Map.entry("--admin-accent-ink", "var(--es-interaction-on-brand)"),
			Map.entry("--admin-sidebar-bg", "var(--es-surface-default)"),
			Map.entry("--admin-sidebar-accent", "var(--es-surface-signature)"),
			Map.entry("--admin-sidebar-accent-border", "var(--es-interaction-on-signature-border)"),
			Map.entry("--admin-primary", "var(--es-interaction-primary)"),
			Map.entry("--admin-primary-hover", "var(--es-interaction-primary-pressed)"),
			Map.entry("--admin-on-primary", "var(--es-interaction-on-primary)"),
			Map.entry("--admin-focus", "var(--es-focus-default)"),
			Map.entry("--admin-focus-on-signature", "var(--es-focus-on-signature)"),
			Map.entry("--admin-good", "var(--es-status-success-content)"),
			Map.entry("--admin-warn", "var(--es-status-warning-content)"),
			Map.entry("--admin-danger", "var(--es-status-danger-content)"),
			Map.entry("--admin-info", "var(--es-status-info-content)"),
			Map.entry("--admin-good-soft", "var(--es-status-success-surface)"),
			Map.entry("--admin-warn-soft", "var(--es-status-warning-surface)"),
			Map.entry("--admin-danger-soft", "var(--es-status-danger-surface)"),
			Map.entry("--admin-info-soft", "var(--es-status-info-surface)")
		);
		assertThat(expectedAliases).hasSize(27);
		Map<String, String> actualAliases = new LinkedHashMap<>();
		expectedAliases.keySet().forEach(name -> actualAliases.put(name, cssProperties.get(name)));
		assertThat(actualAliases).containsExactlyInAnyOrderEntriesOf(expectedAliases);
	}

	@Test
	@DisplayName("관리자·운영자 색 소비자는 semantic token과 chart 역할을 분리한다")
	void adminAndOperatorColorConsumersUseSemanticTokensOnly() throws IOException {
		List<String> rawColors = new ArrayList<>();
		for (String path : CSS_FILES) {
			rawColors.addAll(rawColors(path, read(path)));
		}
		assertThat(rawColors).as("token 선언 밖 CSS raw color: %s", rawColors).isEmpty();

		assertThat(rule(read(CSS_FOUNDATION),
			"\\.admin-v3 a:focus-visible,\\s*\\.admin-v3 button:focus-visible,\\s*"
				+ "\\.admin-v3 input:focus-visible,\\s*\\.admin-v3 select:focus-visible,\\s*"
				+ "\\.admin-v3 textarea:focus-visible,\\s*\\.admin-v3 \\.admin-panel:focus-visible"))
			.contains("outline: 3px solid var(--admin-focus);");
		assertThat(rule(read(CSS_OPERATOR),
			"a:focus-visible,\\s*button:focus-visible,\\s*input:focus-visible"))
			.contains("outline: 3px solid var(--admin-focus);");
		assertThat(rule(read(CSS_SHELL), "\\.admin-nav-item\\.is-active:focus-visible"))
			.contains("outline-color: var(--admin-focus-on-signature);");
		assertThat(rule(read(CSS_SHELL),
			"\\.admin-v3 \\.admin-sidebar \\.admin-nav-workspace-toggle:focus-visible,\\s*"
				+ "\\.admin-v3 \\.admin-sidebar \\.admin-nav-item:focus-visible"))
			.contains("outline-color: var(--admin-on-primary);");
		assertThat(rule(read(CSS_SHELL),
			"\\.admin-v3 \\.admin-topbar \\.admin-sidebar-toggle:focus-visible,\\s*"
				+ "\\.admin-v3 \\.admin-topbar \\.admin-mobile-search:focus-visible,\\s*"
				+ "\\.admin-v3 \\.admin-topbar \\.admin-alert-bell:focus-visible,\\s*"
				+ "\\.admin-v3 \\.admin-topbar \\.admin-user-menu-trigger:focus-visible"))
			.contains("outline-color: var(--admin-on-primary);");
		assertThat(rule(read(CSS_SHELL), "\\.admin-v3 \\.admin-nav-workspace-toggle"))
			.contains("color: var(--admin-on-primary);");
		assertThat(rule(read(CSS_SHELL), "(?m)^\\.admin-nav-item(?=\\s*\\{)"))
			.contains("color: var(--admin-on-primary);");
		assertThat(rule(read(CSS_OPERATOR), "\\.nav-link\\.active:focus-visible"))
			.contains("outline-color: var(--admin-focus-on-signature);");
		assertThat(rule(read(CSS_SHELL), "\\.admin-nav-item\\.is-active"))
			.contains("color: var(--admin-accent-ink);");
		assertThat(rule(read(CSS_OPERATOR), "\\.nav-link\\.active"))
			.contains("color: var(--admin-accent-ink);");
		assertThat(rule(read(CSS_SHELL), "\\.admin-nav-item:hover:focus-visible"))
			.contains("outline-color: var(--admin-focus-on-signature);");
		assertThat(rule(read(CSS_OPERATOR), "\\.nav-link:hover:focus-visible"))
			.contains("outline-color: var(--admin-focus-on-signature);");
		assertThat(rule(read(CSS_SHELL), "\\.admin-nav-item:hover"))
			.contains("border-left-color: var(--admin-sidebar-accent-border);");
		assertThat(rule(read(CSS_SHELL), "\\.admin-nav-item\\.is-active"))
			.contains("border-left-color: var(--admin-sidebar-accent-border);");
		assertThat(rule(read(CSS_OPERATOR), "\\.nav-link:hover"))
			.contains("border-left-color: var(--admin-sidebar-accent-border);");
		assertThat(rule(read(CSS_OPERATOR), "\\.nav-link\\.active"))
			.contains("border-left-color: var(--admin-sidebar-accent-border);");
		assertThat(rule(read(CSS_DATA),
			"\\.admin-v3 \\.timeline \\.timeline-current \\.timeline-time"))
			.contains("color: var(--admin-ink-2);");
		assertThat(read(CSS_COMPONENTS)).contains("color: var(--admin-on-primary);");
		assertThat(read(CSS_OPERATOR)).contains("color: var(--admin-on-primary);");

		for (String path : CHART_JS_FILES) {
			String js = read(path);
			assertThat(js).as("%s tokenColor fallback 금지", path)
				.contains("function tokenColor(name) {")
				.doesNotContain("function tokenColor(name,");
			assertThat(js.replaceAll("(?m)//.*$", ""))
				.as("%s raw HEX fallback 금지", path)
				.doesNotContainPattern(HEX_COLOR);
		}

		String dashboardCharts = read(JS_DASHBOARD_CHARTS);
		assertThat(read(CSS_DATA))
			.contains("@media (max-width: 1024px)")
			.contains(".admin-v3 .dashboard-refresh {")
			.contains("width: 44px;")
			.contains("height: 44px;");
		assertThat(dashboardCharts)
			.contains("document.querySelector('[data-dashboard-snapshot-form]')")
			.contains("tokenColor('--admin-chart-series')")
			.doesNotContain("tokenColor('--admin-good')")
			.doesNotContain("tokenColor('--admin-warn')")
			.doesNotContain("tokenColor('--admin-danger')");
		assertThat(read(JS_BATCH_HISTORY_CHARTS))
			.contains("tokenColor('--admin-good')")
			.contains("tokenColor('--admin-danger')")
			.contains("tokenColor('--admin-warn')")
			.contains("tokenColor('--admin-ink-3')")
			.doesNotContain("--admin-chart-series");
		String operatorReportCharts = read(JS_OPERATOR_REPORT_CHARTS);
		assertThat(operatorReportCharts)
			.contains("var GOOD_LABELS = ['완료', '발송 완료'];")
			.contains("var DANGER_LABELS = ['실패', '발송 실패'];")
			.contains("var goodColor = tokenColor('--admin-good');")
			.contains("var dangerColor = tokenColor('--admin-danger');")
			.contains("var NEUTRAL_SEQUENCE = [\n"
				+ "\t\ttokenColor('--admin-accent'),\n"
				+ "\t\ttokenColor('--admin-ink-2'),\n"
				+ "\t\ttokenColor('--admin-ink-3'),\n"
				+ "\t\ttokenColor('--admin-chart-series')\n"
				+ "\t];")
			.contains("if (GOOD_LABELS.indexOf(label) !== -1) {\n\t\t\t\treturn goodColor;")
			.contains("if (DANGER_LABELS.indexOf(label) !== -1) {\n\t\t\t\treturn dangerColor;");
	}

	@Test
	void rawColorGuardRejectsRawHexInsideAlias() {
		assertThat(rawColors(CSS_TOKENS,
			":root { --new-alias: color-mix(in srgb, var(--es-surface-default), #123456); }"))
			.containsExactly(CSS_TOKENS + ": #123456");
	}

	private static List<String> rawColors(String path, String source) {
		String css = source.replaceAll("(?s)/\\*.*?\\*/", "");
		List<String> rawColors = new ArrayList<>();
		Matcher colors = HEX_COLOR.matcher(css);
		while (colors.find()) {
			String color = colors.group();
			String preceding = css.substring(0, colors.start());
			int declarationStart = Math.max(preceding.lastIndexOf(';'), preceding.lastIndexOf('{')) + 1;
			String declaration = preceding.substring(declarationStart);
			Matcher declarationName = Pattern.compile("\\s*(--[a-z0-9-]+)\\s*:").matcher(declaration);
			String propertyName = declarationName.find() ? declarationName.group(1) : "";
			boolean allowedTokenPrimitive = path.equals(CSS_TOKENS)
				&& propertyName.startsWith("--es-");
			boolean allowedChartSeries = path.equals(CSS_TOKENS)
				&& color.equalsIgnoreCase("#2F6F9F")
				&& propertyName.equals("--admin-chart-series");
			boolean allowedDangerHover = path.equals(CSS_TOKENS)
				&& color.equalsIgnoreCase("#000")
				&& propertyName.equals("--admin-danger-hover");
			if (!allowedTokenPrimitive && !allowedChartSeries && !allowedDangerHover) {
				rawColors.add(path + ": " + color);
			}
		}
		return rawColors;
	}

	@Test
	@DisplayName("admin-v3.css는 tokens → foundation → shell → components → data import manifest다")
	void adminV3IsImportManifest() throws IOException {
		List<String> lines = new ArrayList<>();
		for (String line : read(CSS_ADMIN_V3).split("\n", -1)) {
			String trimmed = line.trim();
			if (!trimmed.isEmpty()) {
				lines.add(trimmed);
			}
		}
		assertThat(lines).containsExactly(
			"@import url(\"/css/admin-tokens.css\");",
			"@import url(\"/css/admin-foundation.css\");",
			"@import url(\"/css/admin-shell.css\");",
			"@import url(\"/css/admin-components.css\");",
			"@import url(\"/css/admin-data.css\");");
	}

	@Test
	@DisplayName("CSS 선언은 네 책임 파일에 단일 owner로 존재하고 중복 block이 없다")
	void responsibilityFilesOwnDeclarationsWithoutDuplication() throws IOException {
		Map<String, Set<String>> ownerFiles = new LinkedHashMap<>();
		for (String path : RESPONSIBILITY_FILES) {
			List<String> blocks = topLevelBlocks(read(path));
			assertThat(blocks).as("%s는 선언을 소유한다", path).isNotEmpty();
			for (String block : blocks) {
				ownerFiles.computeIfAbsent(block, key -> new TreeSet<>()).add(path);
			}
		}
		List<String> duplicated = new ArrayList<>();
		for (Map.Entry<String, Set<String>> entry : ownerFiles.entrySet()) {
			if (entry.getValue().size() > 1) {
				duplicated.add(entry.getValue() + " → " + entry.getKey());
			}
		}
		assertThat(duplicated)
			.as("둘 이상의 책임 파일이 소유한 중복 선언 block: %s", duplicated)
			.isEmpty();
	}

	@Test
	@DisplayName("버튼·panel chrome canonical 명시 class가 존재한다")
	void canonicalButtonAndPanelClassesExist() throws IOException {
		String css = bundle();
		assertThat(css)
			.as("강조 버튼 명시 class(primary)")
			.contains(".admin-v3 button.primary")
			.contains(".admin-btn.primary");
		assertThat(css)
			.as("danger 버튼 명시 class")
			.contains(".admin-v3 button.danger");
		assertThat(css)
			.as("panel chrome 명시 class")
			.contains(".admin-card");
	}

	@Test
	@DisplayName("금지된 초록·청록 계열 hex가 없다")
	void 금지된_초록청록_계열_hex가_없다() throws IOException {
		List<String> violations = new ArrayList<>();
		for (String path : CSS_FILES) {
			String[] lines = read(path).split("\n", -1);
			for (int i = 0; i < lines.length; i++) {
				Matcher m = FORBIDDEN_HEX.matcher(lines[i]);
				while (m.find()) {
					violations.add(path + ":" + (i + 1) + ": " + m.group());
				}
				Matcher rgb = RGB_FUNC.matcher(lines[i]);
				while (rgb.find()) {
					String key = rgb.group(1) + "," + rgb.group(2) + "," + rgb.group(3);
					if (FORBIDDEN_RGB.contains(key)) {
						violations.add(path + ":" + (i + 1) + ": " + rgb.group());
					}
				}
			}
		}
		assertThat(violations)
			.as("금지된 초록·청록 계열 hex 하드코딩(파일:라인:값): %s", violations)
			.isEmpty();
	}

	@Test
	@DisplayName("none이 아닌 box-shadow 선언이 없다")
	void none이_아닌_box_shadow_선언이_없다() throws IOException {
		List<String> violations = new ArrayList<>();
		for (String path : CSS_FILES) {
			String[] lines = read(path).split("\n", -1);
			for (int i = 0; i < lines.length; i++) {
				Matcher m = BOX_SHADOW.matcher(lines[i]);
				if (m.find()) {
					String value = m.group(1).trim();
					if (!BOX_SHADOW_ALLOWED.matcher(value).find()) {
						violations.add(path + ":" + (i + 1) + ": " + value);
					}
				}
			}
		}
		assertThat(violations)
			.as("허용되지 않은 box-shadow(파일:라인:값): %s", violations)
			.isEmpty();
	}

	@Test
	@DisplayName("border-radius 9px 이상 리터럴이 없다")
	void border_radius_9px_이상_리터럴이_없다() throws IOException {
		List<String> violations = new ArrayList<>();
		for (String path : CSS_FILES) {
			String[] lines = read(path).split("\n", -1);
			for (int i = 0; i < lines.length; i++) {
				Matcher decl = BORDER_RADIUS_PX.matcher(lines[i]);
				while (decl.find()) {
					Matcher px = PX_LITERAL.matcher(decl.group(1));
					while (px.find()) {
						int n = Integer.parseInt(px.group(1));
						if (n >= 9 && n < 999) {
							violations.add(path + ":" + (i + 1) + ": " + px.group());
						}
					}
				}
			}
		}
		assertThat(violations)
			.as("border-radius 9px 이상 리터럴(파일:라인:값): %s", violations)
			.isEmpty();
	}

	@Test
	@DisplayName("신고 보조 panel은 flat chrome과 단일 divider만 사용한다")
	void reportSupportPanelsUseFlatChromeWithSingleDivider() throws IOException {
		String css = bundle();
		Pattern flatPanels = Pattern.compile(
			"\\.admin-v3 \\.surge-alert,\\s*\\.admin-v3 \\.processing-time \\{"
				+ "[^}]*border: 0;[^}]*border-radius: 0;[^}]*background: transparent;"
				+ "[^}]*box-shadow: none;",
			Pattern.DOTALL
		);
		Pattern singleDivider = Pattern.compile(
			"\\.admin-v3 \\.surge-alert \\+ \\.processing-time \\{"
				+ "[^}]*border-top: 1px solid var\\(--admin-border\\);",
			Pattern.DOTALL
		);

		assertThat(css).containsPattern(flatPanels);
		assertThat(css).containsPattern(singleDivider);
	}

	@Test
	@DisplayName("mobile topbar는 flat chrome과 44px action target을 유지한다")
	void mobileTopbarUsesFlatChromeAndAccessibleTargets() throws IOException {
		String css = bundle();
		String shell = read("backend/src/main/resources/templates/admin/fragments/shell.html");

		assertThat(css)
			.contains(".admin-v3 .admin-sidebar-toggle,")
			.contains(".admin-v3 .admin-alert-bell,")
			.contains(".admin-v3 .admin-user-menu-trigger,")
			.contains(".admin-v3 .admin-topbar-logout {")
			.contains("min-width: 44px;")
			.contains("min-height: 44px;")
			.contains("border-radius: 0;")
			.contains("background: transparent;")
			.contains("box-shadow: none;");
		assertThat(shell)
			.contains("class=\"admin-topbar-logout-form\"")
			.contains("class=\"admin-topbar-logout\" aria-label=\"로그아웃\"");
		Matcher alertTrigger = Pattern.compile("<a class=\"admin-alert-bell\"[^>]*>").matcher(shell);
		assertThat(alertTrigger.find()).as("alert bell trigger가 존재한다").isTrue();
		assertThat(alertTrigger.group()).doesNotContain("aria-haspopup");
		Matcher userTrigger = Pattern.compile("<button[^>]*class=\"admin-user-menu-trigger\"[^>]*>")
			.matcher(shell);
		assertThat(userTrigger.find()).as("user menu trigger가 존재한다").isTrue();
		assertThat(userTrigger.group()).doesNotContain("aria-haspopup");
		Matcher userPanel = Pattern.compile("<div[^>]*id=\"admin-user-menu-panel\"[^>]*>").matcher(shell);
		assertThat(userPanel.find()).as("user menu panel이 존재한다").isTrue();
		assertThat(userPanel.group())
			.contains("role=\"region\"")
			.doesNotContain("role=\"dialog\"");
	}

	@Test
	@DisplayName("기존 mobile metric divider는 1열에서 단일 top border를 유지한다")
	void mobileMetricDividerKeepsSingleTopBorder() throws IOException {
		String css = bundle();

		assertThat(css)
			.contains(".dashboard-card.metric-cell {")
			.contains("border-left: 0;")
			.contains("border-top: 1px solid var(--admin-border);")
			.contains(".dashboard-card.metric-cell:first-child {")
			.contains("border-top: 0;");
	}

	@Test
	@DisplayName("모든 admin table은 조건과 접근성 이름을 소유한 명시적 scroll wrapper를 사용한다")
	void adminTablesUseExplicitAccessibleScrollWrappers() throws IOException {
		Path templates = ROOT.resolve("backend/src/main/resources/templates/admin");
		List<String> violations = new ArrayList<>();
		int totalTables = 0;
		try (var paths = Files.walk(templates)) {
			for (Path path : paths.filter(file -> file.toString().endsWith(".html")).toList()) {
				String html = Files.readString(path);
				int tables = count(TABLE_TAG, html) - count(KV_TABLE_TAG, html);
				if (tables == 0) {
					continue;
				}
				totalTables += tables;
				int wrappers = count(ACCESSIBLE_TABLE_WRAPPER, html);
				int wrapperCloses = count(TABLE_WRAPPER_CLOSE, html);
				int conditionalTables = count(CONDITIONAL_TABLE, html);
				if (tables != wrappers || tables != wrapperCloses || conditionalTables != 0) {
					violations.add(ROOT.relativize(path) + ": tables=" + tables
						+ ", wrappers=" + wrappers + ", closes=" + wrapperCloses
						+ ", conditionalTables=" + conditionalTables);
				}
			}
		}

		assertThat(totalTables).as("admin table inventory가 비어 있지 않다").isPositive();
		assertThat(violations)
			.as("wrapper 없는 admin table 또는 wrapper 밖 th:if: %s", violations)
			.isEmpty();
	}

	@Test
	@DisplayName("table wrapper만 horizontal scroll을 소유하고 sticky header·첫 열을 유지한다")
	void tableWrapperOwnsHorizontalScrollAndStickyCells() throws IOException {
		String css = bundle();

		assertThat(css)
			.contains(".admin-table-scroll {")
			.contains("max-block-size: min(70vh, 640px);")
			.contains("overflow: auto;")
			.contains(".admin-v3 .admin-table-scroll:focus-visible {")
			.contains(".admin-table-scroll th:first-child,")
			.contains(".admin-table-scroll td:first-child:not([colspan]) {")
			.contains("position: sticky;")
			.contains("left: 0;")
			.contains(".admin-table-scroll thead th:first-child {");
		assertThat(rule(css, "html,\\s*body\\.admin-v3")).contains("overflow-x: hidden;");
		assertThat(rule(css, "\\.admin-main")).doesNotContain("overflow-x: auto;");
		assertThat(rule(css, "\\.admin-panel,\\s*\\.admin-card"))
			.doesNotContain("overflow-x: auto;");
		assertThat(rule(css, "\\.admin-v3 th"))
			.doesNotContain("position: sticky;")
			.doesNotContain("top: 0;");
		assertThat(rule(css, "\\.admin-v3 thead th"))
			.contains("position: sticky;")
			.contains("top: 0;");
		assertThat(rule(css,
			"\\.admin-v3 \\.admin-table-scroll table\\.static-table tbody tr:hover "
				+ "td:first-child:not\\(\\[colspan\\]\\)"))
			.contains("background: var(--admin-surface);");
	}

	@Test
	@DisplayName("표 영역 fragment는 #2071 admin-table-scroll wrapper 계약을 단일 소비한다")
	void tableRegionFragmentConsumesAccessibleScrollWrapperContract() throws IOException {
		String fragment = read("backend/src/main/resources/templates/admin/fragments/table-region.html");

		// wrapper·table·close·조건부 table 계약을 admin table 가드와 동일 규칙으로 고정한다.
		assertThat(count(TABLE_TAG, fragment)).as("table 정확히 1개").isEqualTo(1);
		assertThat(count(ACCESSIBLE_TABLE_WRAPPER, fragment)).as("접근성 scroll wrapper 1개").isEqualTo(1);
		assertThat(count(TABLE_WRAPPER_CLOSE, fragment)).as("wrapper close 1개").isEqualTo(1);
		assertThat(count(CONDITIONAL_TABLE, fragment)).as("table에 th:if 금지").isZero();

		assertThat(fragment)
			.as("region fragment 시그니처")
			.contains("th:fragment=\"region(caption, head, rows)\"")
			// wrapper만 overflow를 소유한다는 계약을 확인하는 표준 aria-label
			.contains("aria-label=\"가로로 스크롤 가능한 데이터 표\"")
			// caption·head·rows를 화면이 주입한다
			.contains("th:text=\"${caption}\"")
			.contains("th:insert=\"${head}\"")
			.contains("th:insert=\"${rows}\"");
	}

	@Test
	@DisplayName("목록 툴바 fragment는 direct control 5개 이하와 시트·no-JS·포커스 복원 계약을 갖는다")
	void listToolbarFragmentPinsUnifiedToolbarContract() throws IOException {
		String fragment = read("backend/src/main/resources/templates/admin/fragments/list-toolbar.html");
		String appJs = read("backend/src/main/resources/static/js/admin/app.js");

		// 단일 toolbar 루트에 검색·저장된 뷰·필터·보기 설정을 모은다.
		assertThat(count(Pattern.compile("class=\"admin-list-toolbar\""), fragment))
			.as("toolbar 루트 1개").isEqualTo(1);
		assertThat(fragment)
			.contains("th:fragment=\"toolbar(basePath, resultsId, search, savedViews, filters, viewSettings)\"")
			.contains("x-data=\"listToolbar\"");

		// direct control ≤5: 검색 입력 1 + 검색 버튼 1 + 저장된 뷰 zone 1 + 필터 트리거 1 + 보기 설정 트리거 1.
		assertThat(count(Pattern.compile("type=\"search\""), fragment)).as("검색 입력 1개").isEqualTo(1);
		assertThat(count(Pattern.compile("<button type=\"submit\" class=\"outline\">검색</button>"), fragment))
			.as("검색 버튼 1개").isEqualTo(1);
		assertThat(count(Pattern.compile("class=\"admin-toolbar-sheet-trigger"), fragment))
			.as("시트 트리거 2개(필터·보기 설정)").isEqualTo(2);

		// HTMX와 full request가 같은 URL·결과: method=get form + hx-get.
		assertThat(fragment)
			.contains("method=\"get\"")
			.contains("hx-get=@{${basePath}}")
			// query parameter·sort·page size 보존용 hidden
			.contains("type=\"hidden\"");

		// 저장된 뷰·필터·보기 설정은 화면이 주입하고, 없으면 렌더하지 않는다(no-JS form/link 유지).
		assertThat(fragment)
			.contains("th:replace=\"${savedViews}\"")
			.contains("th:replace=\"${filters}\"")
			.contains("th:replace=\"${viewSettings}\"");

		// 시트 트리거는 x-cloak라 no-JS에서 숨고, 시트는 outside close(입력 미유실)와 Esc 포커스 복원을 갖는다.
		assertThat(fragment)
			.contains("x-cloak")
			.contains("x-bind:aria-expanded=\"filterExpanded\"")
			.contains("x-bind:aria-expanded=\"viewExpanded\"")
			.contains("aria-controls=\"admin-toolbar-filter-sheet\"")
			.contains("aria-controls=\"admin-toolbar-view-sheet\"")
			.contains("x-on:click.outside=\"closeFilter\"")
			.contains("x-on:click.outside=\"closeView\"")
			.contains("x-on:keydown.escape.window=\"closeFilterFromKeyboard\"")
			.contains("x-on:keydown.escape.window=\"closeViewFromKeyboard\"")
			.contains("x-bind:class=\"filterSheetClass\"")
			.contains("x-bind:class=\"viewSheetClass\"");

		// app.js listToolbar 컴포넌트: close는 상태만 닫고(입력 미유실), keyboard close만 포커스를 복원한다.
		assertThat(appJs)
			.contains("Alpine.data('listToolbar'")
			.contains("closeFilterFromKeyboard")
			.contains("closeViewFromKeyboard")
			.contains("this.$refs.filterTrigger?.focus();")
			.contains("this.$refs.viewTrigger?.focus();");

		// listener 중복 등록 0: 시트 로직은 Alpine 디렉티브로만 선언하고 수동 리스너·폴링을 쓰지 않는다.
		String listToolbarBlock = appJs.substring(appJs.indexOf("Alpine.data('listToolbar'"));
		assertThat(listToolbarBlock)
			.as("listToolbar 컴포넌트는 수동 addEventListener·setInterval를 등록하지 않는다")
			.doesNotContain("addEventListener")
			.doesNotContain("setInterval");
	}

	@Test
	@DisplayName("데이터팩·공통코드 목록 표 헤더는 영문 필드명 원문을 쓰지 않는다")
	void datapackAndCodesTableHeadersAreKorean() throws IOException {
		List<Path> targets = new ArrayList<>();
		Path datapackTemplates = ROOT.resolve("backend/src/main/resources/templates/admin/datapack");
		try (var paths = Files.walk(datapackTemplates)) {
			paths.filter(path -> path.toString().endsWith("list.html")).forEach(targets::add);
		}
		targets.add(ROOT.resolve("backend/src/main/resources/templates/admin/codes/list.html"));

		List<String> violations = new ArrayList<>();
		for (Path path : targets) {
			String html = Files.readString(path);
			Matcher matcher = STATIC_TH.matcher(html);
			while (matcher.find()) {
				String attrs = matcher.group(1);
				if (attrs.contains("th:text")) {
					continue;
				}
				String text = matcher.group(2).trim();
				if (text.isEmpty() || text.equals("ID")) {
					continue;
				}
				if (FORBIDDEN_TABLE_HEADER_TOKENS.contains(text.toLowerCase(Locale.ROOT))) {
					violations.add(ROOT.relativize(path) + ": <th>" + text + "</th>");
				}
			}
		}

		assertThat(violations)
			.as("영문 필드명 원문 표 헤더(파일: <th>텍스트</th>): %s", violations)
			.isEmpty();
	}

	@Test
	@DisplayName("운영자 섹션 제목에 단독 영문 Group/Code/Override/Evidence 를 쓰지 않는다")
	void operatorSectionTitlesAreKorean() throws IOException {
		Set<String> forbiddenExact = Set.of("Group", "Code", "Override 요청", "Evidence");
		Path templates = ROOT.resolve("backend/src/main/resources/templates/admin");
		List<String> violations = new ArrayList<>();
		try (var paths = Files.walk(templates)) {
			for (Path path : paths.filter(path -> path.toString().endsWith(".html")).toList()) {
				String html = Files.readString(path);
				Matcher matcher = H2_TAG.matcher(html);
				while (matcher.find()) {
					String text = matcher.group(1).trim();
					boolean violates = forbiddenExact.contains(text)
						|| text.startsWith("Code ")
						|| text.equals("Alias")
						|| text.startsWith("Alias ");
					if (violates) {
						violations.add(ROOT.relativize(path) + ": <h2>" + text + "</h2>");
					}
				}
			}
		}

		assertThat(violations)
			.as("영문 단독·혼재 섹션 제목(파일: <h2>텍스트</h2>): %s", violations)
			.isEmpty();
	}

	@Test
	@DisplayName("주 메뉴 토글은 데스크톱 숨김·좁은 화면 topbar leading 계약을 CSS에 유지한다")
	void sidebarToggleDesktopHiddenNarrowTopbarLeading() throws IOException {
		String components = read(CSS_COMPONENTS);

		assertThat(components)
			.as(">1024px 기본: 사이드바 토글 숨김")
			.containsPattern(Pattern.compile(
				"\\.admin-v3 \\.admin-sidebar-toggle \\{[^}]*display:\\s*none;", Pattern.DOTALL));

		Matcher narrowMedia = Pattern.compile("@media \\(max-width: 1024px\\)\\s*\\{(.*)", Pattern.DOTALL)
			.matcher(components);
		assertThat(narrowMedia.find()).as("≤1024px media query가 존재한다").isTrue();
		assertThat(narrowMedia.group(1))
			.as("≤1024px: nav-control 안 토글을 inline-flex(또는 flex)로 노출")
			.containsPattern(Pattern.compile(
				"\\.admin-v3 \\.admin-sidebar-nav-control \\.admin-sidebar-toggle \\{[^}]*display:\\s*(inline-flex|flex);",
				Pattern.DOTALL));

		String shell = read("backend/src/main/resources/templates/admin/fragments/shell.html");
		assertThat(shell)
			.as("toggle는 admin-topbar-context 안 첫 컨트롤이다")
			.containsPattern(Pattern.compile(
				"<div class=\"admin-topbar-context\">\\s*(?:<!--.*?-->\\s*)?<div class=\"admin-sidebar-nav-control\"",
				Pattern.DOTALL))
			.doesNotContain("☰")
			.contains("icon('menu')")
			.contains("aria-label=\"주 메뉴 열기·닫기\"");
	}

	@Test
	@DisplayName("데스크톱 사이드바는 뷰포트에 붙고 shell은 화면을 자르지 않는다")
	void desktopSidebarIsFlushAndShellDoesNotClip() throws IOException {
		String shell = read(CSS_SHELL);

		assertThat(rule(shell, "\\.admin-shell"))
			.contains("overflow: visible;");
		assertThat(rule(shell, "\\.admin-sidebar"))
			.contains("height: 100vh;")
			.contains("height: 100dvh;")
			.contains("margin: 0;")
			.contains("border-left: 0;")
			.contains("border-radius: 0;");
	}

	@Test
	@DisplayName("접힌 workspace의 program 목록은 작성자 display 규칙보다 hidden 상태가 우선한다")
	void collapsedWorkspaceProgramsStayHidden() throws IOException {
		assertThat(rule(read(CSS_SHELL), "\\.admin-nav-workspace-programs\\[hidden\\]"))
			.contains("display: none;");
	}

	@Test
	@DisplayName("workspace 클릭은 버튼이 아니라 nav root를 기준으로 형제 그룹을 정리한다")
	void workspaceToggleUsesCapturedNavigationRoot() throws IOException {
		String appJs = read("backend/src/main/resources/static/js/admin/app.js");

		assertThat(appJs)
			.contains("var root = null;")
			.contains("root = this.$el;")
			.contains("return Array.from(root.children)")
			.contains("workspace.dataset.persistent === 'true'")
			.contains("candidate.dataset.persistent !== 'true'");
	}

	@Test
	@DisplayName("관리자 화면은 잘리지 않는 활성 메뉴와 넓은 검색·동일한 제목 위계를 공유한다")
	void adminPagesShareReferenceShellFamilyLook() throws IOException {
		String shell = read(CSS_SHELL);
		String data = read(CSS_DATA);
		String shellTemplate = read("backend/src/main/resources/templates/admin/fragments/shell.html");

		assertThat(rule(shell, "\\.admin-nav-item\\.is-active"))
			.contains("border-left-color: var(--admin-sidebar-accent-border);")
			.contains("background: var(--admin-sidebar-accent);")
			.contains("color: var(--admin-accent-ink);")
			.doesNotContain("min-height:")
			.doesNotContain("margin:")
			.doesNotContain("padding:")
			.doesNotContain("border-radius:");
		assertThat(shellTemplate)
			.contains("th:if=\"${isPersistent}\" class=\"admin-nav-program-icon\"")
			.doesNotContain("active == program.id or isPersistent");
		assertThat(rule(shell, "(?m)^\\.admin-topbar-search"))
			.contains("width: min(520px, 44vw);")
			.contains("min-width: 360px;");
		assertThat(rule(shell, "\\.admin-mobile-search svg"))
			.contains("width: 22px;")
			.contains("height: 22px;");
		assertThat(rule(shell, "\\.admin-dashboard-page \\.admin-topbar-search"))
			.doesNotContain("width: 230px;")
			.doesNotContain("min-width: 230px;");
		assertThat(shell)
			.doesNotContain(".admin-dashboard-page .admin-topbar-actions")
			.contains("grid-template-columns: minmax(0, 1fr) 44px auto;")
			.contains("height: 60px;")
			.doesNotContain("grid-column: 1 / -1;")
			.doesNotContain("margin: 8px -12px 0;");
		assertThat(data)
			.doesNotContain("background: var(--admin-good-vivid);")
			.doesNotContain("background: var(--admin-warn-vivid);")
			.doesNotContain("color: var(--admin-good-vivid);")
			.doesNotContain("color: var(--admin-warn-vivid);");
		assertThat(rule(shell, "\\.admin-page-head h1"))
			.contains("font-size: var(--admin-fs-page);")
			.contains("letter-spacing: -0.04em;");
		assertThat(rule(shell, "\\.admin-page-head p"))
			.doesNotContain("display: none;")
			.contains("color: var(--admin-ink-2);");
	}

	@Test
	@DisplayName("모바일 사이드바는 펼친 업무 탭을 구분선으로 묶는다")
	void mobileSidebarSeparatesExpandedWorkspace() throws IOException {
		assertThat(rule(read(CSS_SHELL),
			"\\.admin-nav-workspace:not\\(\\.is-persistent\\):has\\(> \\.admin-nav-workspace-toggle\\[aria-expanded=\"true\"\\]\\)"))
			.contains("border-block-color:")
			.contains("background:");
	}

	@Test
	@DisplayName("모바일 오프캔버스는 뷰포트 높이에 고정하고 메뉴 목록만 스크롤한다")
	void mobileSidebarKeepsLongNavigationReachable() throws IOException {
		String shell = read(CSS_SHELL);

		assertThat(rule(shell, "\\.has-js \\.admin-sidebar"))
			.contains("height: 100dvh;")
			.contains("min-height: 0;")
			.contains("max-height: 100dvh;")
			.contains("overflow: hidden;");
		assertThat(rule(shell, "\\.admin-nav-scroll"))
			.contains("min-height: 0;")
			.contains("overflow-y: auto;");
	}

	@Test
	@DisplayName("모바일 상단 브랜드는 메뉴 버튼 옆에서 왼쪽 정렬하고 읽기 쉬운 크기를 쓴다")
	void mobileTopbarBrandAlignsWithMenuButton() throws IOException {
		String shell = read(CSS_SHELL);

		assertThat(rule(shell, "\\.admin-topbar-mobile-brand"))
			.contains("justify-content: flex-start;")
			.contains("padding-left: 4px;");
		assertThat(rule(shell, "\\.admin-topbar-mobile-brand \\.admin-topbar-brand"))
			.contains("font-size: 16px;");
	}

	@Test
	@DisplayName("모바일 로그인 브랜드는 중앙 위계를 유지하면서 제목 가까이에 배치한다")
	void mobileLoginBrandUsesCompactCenteredLockup() throws IOException {
		String components = read(CSS_COMPONENTS);

		assertThat(rule(components, "\\.admin-login-page \\.login-brand-panel"))
			.contains("margin-bottom: 12px;");
		assertThat(rule(components, "\\.admin-login-page \\.login-brand-lockup img"))
			.contains("width: 40px;")
			.contains("height: 40px;");
		assertThat(rule(components, "\\.admin-login-page \\.login-brand-lockup strong"))
			.contains("font-size: 20px;");
	}

	@Test
	@DisplayName("관리자 로그인은 분할 hero 없이 하나의 중앙 form 흐름을 사용한다")
	void adminLoginUsesSingleCenteredFlow() throws IOException {
		String components = read(CSS_COMPONENTS);

		assertThat(rule(components, "\\.login-layout"))
			.contains("grid-template-columns: 1fr;")
			.contains("place-content: center;")
			.contains("min-height: 100dvh;")
			.doesNotContain("0.82fr")
			.doesNotContain("1.18fr");
		assertThat(rule(components, "\\.login-brand-panel"))
			.contains("background: transparent;")
			.contains("color: var(--admin-ink);");
		assertThat(rule(components, "\\.admin-login-page \\.login-brand-panel"))
			.contains("justify-content: center;");
		assertThat(rule(components, "\\.admin-login-page \\.login-brand-lockup strong"))
			.contains("font-size: 20px;");
	}

	@Test
	@DisplayName("모바일 상단바는 검색·알림·계정 아이콘만 한 행에 둔다")
	void mobileTopbarUsesCompactIconActions() throws IOException {
		String shellTemplate = read("backend/src/main/resources/templates/admin/fragments/shell.html");
		String components = read(CSS_COMPONENTS);

		assertThat(shellTemplate)
			.contains("class=\"admin-user-menu-icon\"")
			.contains("icon('account')");
		assertThat(rule(components, "\\.admin-v3 \\.admin-user-menu-trigger"))
			.contains("width: 44px;")
			.contains("padding: 0;")
			.contains("border: 0;");
		assertThat(rule(components, "\\.admin-v3 \\.admin-alert-center \\.admin-alert-panel"))
			.contains("position: fixed;")
			.contains("top: 60px;")
			.contains("left: 10px;")
			.contains("right: 10px;")
			.contains("width: auto;");
		assertThat(count(Pattern.compile("\\.admin-alert-center \\.admin-alert-panel"), components))
			.isEqualTo(2);
		assertThat(components.indexOf(".admin-v3 .admin-alert-center .admin-alert-panel"))
			.as("모바일 알림 override는 기본 패널 규칙 뒤에 온다")
			.isGreaterThan(components.indexOf(".admin-alert-center .admin-alert-panel"));
	}

	@Test
	@DisplayName("주간 요일은 간결하게 표시하고 확인 필요는 가로 스트립을 사용한다")
	void dashboardWeeklyAndTriageKeepVisualBreathingRoom() throws IOException {
		String data = read(CSS_DATA);
		String shell = read(CSS_SHELL);
		String dashboard = read("backend/src/main/resources/templates/admin/dashboard.html");

		assertThat(rule(data, "\\.dashboard-week-days"))
			.contains("margin: 14px 0 0;");
		assertThat(rule(data, "\\.dashboard-basis-time"))
			.contains("font-size: var(--admin-fs-meta);");
		assertThat(rule(data, "\\.dashboard-week-day\\.is-today"))
			.contains("border-color: var(--admin-border-strong);")
			.doesNotContain("var(--es-");
		assertThat(rule(data, "\\.dashboard-triage-list"))
			.contains("display: grid;")
			.contains("grid-template-columns: repeat(auto-fit, minmax(180px, 1fr));");
		assertThat(data)
			.contains(".dashboard-operations-details")
			.contains("@media (max-width: 1200px) {\n\t.dashboard-overview-grid {\n\t\tgrid-template-columns: 1fr;")
			.doesNotContain(".dashboard-week-day.is-weekend:not(.is-today)")
			.doesNotContain(".admin-dashboard-page .dashboard-urgent,")
			.doesNotContain(".dashboard-overview-grid,\n\t.dashboard-reference-lower");
		assertThat(rule(shell, "(?m)^\\.admin-topbar-row"))
			.contains("box-shadow: none;");
		assertThat(rule(shell, "\\.admin-topbar-search:focus-within"))
			.contains("outline: 3px solid var(--admin-focus);");
		assertThat(dashboard.indexOf("dashboardUrgentItems"))
			.as("긴급 신호는 기본 접힘 상세 지표보다 먼저 렌더한다")
			.isLessThan(dashboard.indexOf("<details class=\"dashboard-operations-details\">"));
	}

	@Test
	@DisplayName("관리자 productive UI는 다섯 단계 글자 크기와 두 단계 굵기를 사용한다")
	void adminTypographyUsesProductiveRoleScale() throws IOException {
		String tokens = read(CSS_TOKENS);

		assertThat(tokens)
			.contains("--admin-fs-page: 28px;")
			.contains("--admin-fs-section: 20px;")
			.contains("--admin-fs-body: 16px;")
			.contains("--admin-fs-control: 14px;")
			.contains("--admin-fs-meta: 12px;")
			.contains("--admin-w-body: 400;")
			.contains("--admin-w-strong: 600;")
			.contains("--admin-w-title: 600;");
	}

	private static int count(Pattern pattern, String source) {
		int count = 0;
		Matcher matcher = pattern.matcher(source);
		while (matcher.find()) {
			count++;
		}
		return count;
	}

	private static String primitiveProperty(String token) {
		return "--es-" + token.replace(".", "-")
			.replaceAll("([a-z])([A-Z])", "$1-$2").toLowerCase(Locale.ROOT);
	}

	private static Map<String, String> cssCustomProperties(String css) {
		Map<String, String> properties = new LinkedHashMap<>();
		Matcher matcher = CSS_CUSTOM_PROPERTY.matcher(css);
		while (matcher.find()) {
			properties.put(matcher.group(1), matcher.group(2).trim());
		}
		return properties;
	}

	private static String rule(String css, String selectorPattern) {
		Matcher matcher = Pattern.compile(selectorPattern + "\\s*\\{([^}]*)}", Pattern.DOTALL).matcher(css);
		assertThat(matcher.find()).as("CSS rule이 존재한다: %s", selectorPattern).isTrue();
		return matcher.group(1);
	}

	// 4책임 파일을 import 순서(foundation → shell → components → data)로 이어붙인 실효 스타일시트.
	// admin-v3.css의 @import가 만드는 cascade와 동일한 선언 집합을 검증 대상으로 삼는다.
	private static String bundle() throws IOException {
		StringBuilder sb = new StringBuilder();
		for (String path : RESPONSIBILITY_FILES) {
			sb.append(read(path)).append("\n");
		}
		return sb.toString();
	}

	// 주석을 제거한 뒤 depth 0의 balanced {..} block을 selector와 함께 추출한다(@media 등 중첩은 한 block).
	private static List<String> topLevelBlocks(String css) {
		String noComments = css.replaceAll("(?s)/\\*.*?\\*/", "");
		List<String> blocks = new ArrayList<>();
		int depth = 0;
		int blockStart = 0;
		int selStart = 0;
		for (int i = 0; i < noComments.length(); i++) {
			char c = noComments.charAt(i);
			if (c == '{') {
				if (depth == 0) {
					blockStart = selStart;
				}
				depth++;
			} else if (c == '}') {
				depth--;
				if (depth == 0) {
					String block = noComments.substring(blockStart, i + 1).trim().replaceAll("\\s+", " ");
					if (!block.isEmpty()) {
						blocks.add(block);
					}
					selStart = i + 1;
				}
			}
		}
		return blocks;
	}

	private static String read(String path) throws IOException {
		return Files.readString(ROOT.resolve(path));
	}
}
