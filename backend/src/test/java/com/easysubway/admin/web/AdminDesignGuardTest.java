package com.easysubway.admin.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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
	private static final Pattern CONDITIONAL_TABLE = Pattern.compile("<table\\b[^>]*\\bth:if=");
	private static final Pattern ACCESSIBLE_TABLE_WRAPPER = Pattern.compile(
		"<div class=\"admin-table-scroll\" tabindex=\"0\" role=\"group\"\\s+"
			+ "aria-label=\"가로로 스크롤 가능한 [^\"]*표\"[^>]*>\\s*<table\\b",
		Pattern.DOTALL);
	private static final Pattern TABLE_WRAPPER_CLOSE = Pattern.compile("</table>\\s*</div>");

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
