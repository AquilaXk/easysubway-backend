package com.easysubway.admin.fragments;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.StringWriter;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;

/**
 * admin/fragments/missing-value.html의 결측 판정 조건(#2349 PR⑩b 리뷰 반영)을 Spring MVC 없이
 * 직접 렌더해 검증한다. null·"—"뿐 아니라 빈/공백 문자열도 결측 분기로 가야 하고,
 * raw로 문자열이 아닌 값(Integer 등, 추이 표 호출부에서 사용)도 안전하게 처리되어야 한다.
 */
class MissingValueFragmentTest {

	private static final String MISSING_MARKUP =
		"<span aria-hidden=\"true\">—</span><span class=\"sr-only\">미상</span>";

	private final TemplateEngine templateEngine = newTemplateEngine();

	private static TemplateEngine newTemplateEngine() {
		ClassLoaderTemplateResolver resolver = new ClassLoaderTemplateResolver();
		resolver.setPrefix("templates/");
		resolver.setSuffix(".html");
		resolver.setTemplateMode(TemplateMode.HTML);
		resolver.setCacheable(false);
		// 운영 렌더링과 동일하게 SpringTemplateEngine(SpringStandardDialect/SpEL)을 사용한다.
		// 기본 TemplateEngine(OGNL)은 이 프로젝트가 ognl 의존성을 끌어오지 않아 테스트 클래스패스에 없다.
		SpringTemplateEngine engine = new SpringTemplateEngine();
		engine.setTemplateResolver(resolver);
		return engine;
	}

	@Test
	void nullSentinelAndBlankStringsAllRenderAsMissing() {
		assertThat(render(null)).isEqualTo(MISSING_MARKUP);
		assertThat(render("—")).isEqualTo(MISSING_MARKUP);
		assertThat(render("")).isEqualTo(MISSING_MARKUP);
		assertThat(render("   ")).isEqualTo(MISSING_MARKUP);
	}

	@Test
	void nonBlankStringRendersAsPlainText() {
		assertThat(render("candidate-1")).isEqualTo("candidate-1");
	}

	@Test
	void nonStringRawIsHandledSafely() {
		assertThat(render(7)).isEqualTo("7");
	}

	private String render(Object raw) {
		// th:fragment="value(raw, srText)"는 매개변수 목록을 서명으로 선언할 뿐이므로, 이름이 같은
		// 컨텍스트 변수를 미리 채워 두면 셀렉터만으로도(인자 표현식 없이) 동일하게 바인딩된다.
		Context context = new Context();
		context.setVariable("raw", raw);
		context.setVariable("srText", "미상");
		StringWriter writer = new StringWriter();
		templateEngine.process("admin/fragments/missing-value", Set.of("value"), context, writer);
		return writer.toString();
	}
}
