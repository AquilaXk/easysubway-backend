package com.easysubway.operator.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("운영기관 UI v4 계약")
class OperatorUiV4ContractTest {

	private static final Path ROOT = Path.of("..").toAbsolutePath().normalize();

	@Test
	@DisplayName("운영기관 리포트 화면은 전용 셸 fragment를 공유하고 admin 검색·알림을 노출하지 않는다")
	void operatorReportsUseSharedShell() throws Exception {
		String shell = read("backend/src/main/resources/templates/operator/fragments/shell.html");
		assertThat(shell)
			.contains("th:fragment=\"sidebar(active)\"")
			.contains("th:fragment=\"topbar(pageName, note)\"")
			.contains("th:fragment=\"chartScripts\"")
			.doesNotContain("command-palette")
			.doesNotContain("admin-alert-center");

		for (String template : reportTemplates()) {
			String html = read(template);
			assertThat(html)
				.contains("<link rel=\"icon\" href=\"data:,\">")
				.contains("operator/fragments/shell :: sidebar")
				.contains("operator/fragments/shell :: topbar")
				.contains("operator/fragments/shell :: chartScripts")
				.doesNotContain("command-palette")
				.doesNotContain("admin-alert-center");
		}
	}

	@Test
	@DisplayName("operator CSS는 admin token 파일만 공유하고 자체 :root 토큰을 선언하지 않는다")
	void operatorCssUsesAdminTokensOnly() throws Exception {
		String adminTokens = read("backend/src/main/resources/static/css/admin-tokens.css");
		String adminCss = read("backend/src/main/resources/static/css/admin-v3.css");
		String operatorCss = read("backend/src/main/resources/static/css/operator-v3.css");

		assertThat(adminTokens)
			.contains(":root")
			.contains("--admin-bg")
			.contains("--admin-accent");
		assertThat(adminCss).startsWith("@import url(\"/css/admin-tokens.css\");");
		assertThat(operatorCss)
			.startsWith("@import url(\"/css/admin-tokens.css\");")
			.doesNotContain(":root")
			.doesNotContain("--canvas")
			.doesNotContain("--surface")
			.contains("var(--admin-bg)")
			.contains("var(--admin-accent)");
	}

	private static List<String> reportTemplates() {
		return List.of(
			"backend/src/main/resources/templates/operator/accessibility-report.html",
			"backend/src/main/resources/templates/operator/data-collection-failures.html",
			"backend/src/main/resources/templates/operator/push-notification-report.html",
			"backend/src/main/resources/templates/operator/repeated-broken-facilities.html",
			"backend/src/main/resources/templates/operator/route-feedback-report.html"
		);
	}

	private static String read(String path) throws IOException {
		return Files.readString(ROOT.resolve(path));
	}
}
