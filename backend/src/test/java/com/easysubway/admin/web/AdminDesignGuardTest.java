package com.easysubway.admin.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("관리자 디자인 가드")
class AdminDesignGuardTest {

	private static final Path ROOT = Path.of("..").toAbsolutePath().normalize();

	private static final List<String> CSS_FILES = List.of(
		"backend/src/main/resources/static/css/admin-tokens.css",
		"backend/src/main/resources/static/css/admin-v3.css",
		"backend/src/main/resources/static/css/operator-v3.css");

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
	private static final Pattern CONDITIONAL_TABLE = Pattern.compile("<table\\b[^>]*\\bth:if=");
	private static final Pattern ACCESSIBLE_TABLE_WRAPPER = Pattern.compile(
		"<div class=\"admin-table-scroll\" tabindex=\"0\" role=\"group\"\\s+"
			+ "aria-label=\"가로로 스크롤 가능한 [^\"]*표\"[^>]*>\\s*<table\\b",
		Pattern.DOTALL);
	private static final Pattern TABLE_WRAPPER_CLOSE = Pattern.compile("</table>\\s*</div>");

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
		String css = read("backend/src/main/resources/static/css/admin-v3.css");
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
		String css = read("backend/src/main/resources/static/css/admin-v3.css");
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
		String css = read("backend/src/main/resources/static/css/admin-v3.css");

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
				int tables = count(TABLE_TAG, html);
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
		String css = read("backend/src/main/resources/static/css/admin-v3.css");

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
		assertThat(rule(css, "\\.admin-v3 section,\\s*\\.admin-card"))
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

	private static int count(Pattern pattern, String source) {
		int count = 0;
		Matcher matcher = pattern.matcher(source);
		while (matcher.find()) {
			count++;
		}
		return count;
	}

	private static String rule(String css, String selectorPattern) {
		Matcher matcher = Pattern.compile(selectorPattern + "\\s*\\{([^}]*)}", Pattern.DOTALL).matcher(css);
		assertThat(matcher.find()).as("CSS rule이 존재한다: %s", selectorPattern).isTrue();
		return matcher.group(1);
	}

	private static String read(String path) throws IOException {
		return Files.readString(ROOT.resolve(path));
	}
}
