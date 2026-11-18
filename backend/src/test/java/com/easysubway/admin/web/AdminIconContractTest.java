package com.easysubway.admin.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("관리자 아이콘 sprite 계약")
class AdminIconContractTest {

	private static final Path ROOT = Path.of("..").toAbsolutePath().normalize();

	private static final String SPRITE = "backend/src/main/resources/static/icons/admin-symbols.svg";
	private static final String FRAGMENT = "backend/src/main/resources/templates/admin/fragments/icon.html";

	// #2666: 관리자 셸에서 사용하는 공통 동작·workspace 아이콘 inventory를 고정한다.
	private static final List<String> INVENTORY = List.of(
		"search", "menu", "alert", "close", "account", "logout", "chevron-down",
		"check", "warning", "error", "info", "more", "copy", "refresh", "dashboard",
		"data-quality", "operations", "communications", "analytics", "datapack", "system-audit");

	private static final Pattern SYMBOL = Pattern.compile("<symbol\\b[^>]*>", Pattern.DOTALL);
	private static final Pattern SYMBOL_ID = Pattern.compile("id=\"([^\"]+)\"");
	// 신규 raw color 금지: sprite 색은 currentColor/none만 허용한다(주석 제거 후 스캔).
	private static final Pattern XML_COMMENT = Pattern.compile("<!--.*?-->", Pattern.DOTALL);
	private static final Pattern HEX_COLOR = Pattern.compile("#[0-9a-fA-F]{3,8}\\b");
	private static final Pattern RGB_HSL = Pattern.compile("\\b(?:rgba?|hsla?)\\s*\\(", Pattern.CASE_INSENSITIVE);
	// 외부 라이브러리 vendor 금지: href/src/url 원격 참조 없음(xmlns 네임스페이스 URI는 제외).
	private static final Pattern EXTERNAL_REF = Pattern.compile(
		"(?:(?:xlink:)?href|src)\\s*=\\s*[\"']?\\s*https?://"
			+ "|url\\(\\s*[\"']?\\s*https?://",
		Pattern.CASE_INSENSITIVE);
	private static final Pattern RX_LITERAL = Pattern.compile("\\brx=\"([0-9.]+)\"");

	@Test
	@DisplayName("sprite는 고정 inventory 21개 symbol을 정확히 소유한다")
	void spriteOwnsFixedInventory() throws IOException {
		String svg = read(SPRITE);
		List<String> ids = new ArrayList<>();
		Matcher symbols = SYMBOL.matcher(svg);
		while (symbols.find()) {
			Matcher id = SYMBOL_ID.matcher(symbols.group());
			assertThat(id.find()).as("symbol에 id가 있다: %s", symbols.group()).isTrue();
			ids.add(id.group(1));
		}
		assertThat(ids)
			.as("sprite symbol id 집합이 고정 inventory와 정확히 일치한다")
			.containsExactlyInAnyOrderElementsOf(INVENTORY)
			.hasSize(INVENTORY.size());
		assertThat(new LinkedHashSet<>(ids))
			.as("중복 symbol id가 없다")
			.hasSize(ids.size());
	}

	@Test
	@DisplayName("각 symbol은 20x20 뷰박스와 currentColor 라인 스펙을 사용한다")
	void eachSymbolUsesLineSpec() throws IOException {
		String svg = read(SPRITE);
		Matcher symbols = SYMBOL.matcher(svg);
		int checked = 0;
		while (symbols.find()) {
			String tag = symbols.group();
			assertThat(tag)
				.as("symbol 스펙: %s", tag)
				.contains("viewBox=\"0 0 20 20\"")
				.contains("fill=\"none\"")
				.contains("stroke=\"currentColor\"")
				.contains("stroke-width=\"1.75\"")
				.contains("stroke-linecap=\"round\"")
				.contains("stroke-linejoin=\"round\"");
			checked++;
		}
		assertThat(checked).isEqualTo(INVENTORY.size());
	}

	@Test
	@DisplayName("sprite root svg는 보조기술에서 감춰지고 raw color·외부 참조가 없다")
	void spriteRootHiddenAndSelfContained() throws IOException {
		String svg = read(SPRITE);
		Matcher root = Pattern.compile("<svg\\b[^>]*>").matcher(svg);
		assertThat(root.find()).as("root svg가 존재한다").isTrue();
		assertThat(root.group())
			.as("root svg는 aria-hidden·focusable=false")
			.contains("aria-hidden=\"true\"")
			.contains("focusable=\"false\"");

		String svgNoComments = XML_COMMENT.matcher(svg).replaceAll("");
		assertThat(HEX_COLOR.matcher(svgNoComments).find()).as("sprite에 raw hex color가 없다").isFalse();
		assertThat(RGB_HSL.matcher(svgNoComments).find()).as("sprite에 rgb/hsl color 함수가 없다").isFalse();
		assertThat(EXTERNAL_REF.matcher(svg).find())
			.as("sprite에 원격(외부 라이브러리) 참조가 없다").isFalse();

		List<String> badRadius = new ArrayList<>();
		Matcher rx = RX_LITERAL.matcher(svg);
		while (rx.find()) {
			double value = Double.parseDouble(rx.group(1));
			if (value >= 9 && value < 999) {
				badRadius.add(rx.group());
			}
		}
		assertThat(badRadius).as("9~998px radius가 없다: %s", badRadius).isEmpty();
	}

	@Test
	@DisplayName("icon fragment는 aria-hidden svg로 same-origin sprite를 참조한다")
	void iconFragmentRendersHiddenUse() throws IOException {
		String fragment = read(FRAGMENT);
		assertThat(fragment).contains("th:fragment=\"icon(name)\"");

		Matcher svg = Pattern.compile("<svg\\b[^>]*th:fragment=\"icon\\(name\\)\"[^>]*>").matcher(fragment);
		assertThat(svg.find()).as("icon fragment svg가 존재한다").isTrue();
		assertThat(svg.group())
			.as("fragment svg는 aria-hidden·focusable=false")
			.contains("aria-hidden=\"true\"")
			.contains("focusable=\"false\"");

		assertThat(fragment)
			.as("same-origin sprite를 <use>로 참조한다")
			.contains("<use")
			.contains("/icons/admin-symbols.svg");
		assertThat(EXTERNAL_REF.matcher(fragment).find())
			.as("fragment에 원격 참조가 없다").isFalse();
	}

	@Test
	@DisplayName("sprite와 fragment 파일이 프로젝트 소유 경로에 존재한다")
	void projectOwnedAssetsExist() {
		assertThat(Files.exists(ROOT.resolve(SPRITE))).as("sprite 파일 존재").isTrue();
		assertThat(Files.exists(ROOT.resolve(FRAGMENT))).as("fragment 파일 존재").isTrue();
	}

	private static String read(String path) throws IOException {
		return Files.readString(ROOT.resolve(path));
	}
}
