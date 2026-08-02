package com.easysubway.contract;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.DefaultParameterNameDiscoverer;
import org.springframework.core.MethodParameter;
import org.springframework.core.ParameterNameDiscoverer;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.ValueConstants;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.yaml.snakeyaml.Yaml;

/**
 * 공개 OpenAPI 계약 파일의 <strong>구조</strong>가 실제 controller와 어긋나면 실패하는 계약 게이트.
 *
 * <p>계약 파일은 저장소 밖의 클라이언트가 요청을 조립하는 정본이므로, 어긋난 채로 남아 있으면
 * 서버가 거부하는 요청이 생성된다. 그 어긋남이 아무 경보 없이 남아 있던 것이 이 게이트를 만든
 * 이유다(이슈 #37).
 *
 * <p><strong>검사하는 것</strong>은 아래 각 테스트의 {@code @DisplayName}이 말하는 범위가 전부다 —
 * endpoint 실재와 소유 경로 완전성, Spring이 강제하는 binding의 required 선언, 유령 parameter
 * 부재, 요청 본문의 유무·required·media type, 성공 응답의 상태 코드와 본문 유무,
 * security scheme을 참조하는 operation의 401 선언, component 참조.
 *
 * <p><strong>검사하지 않는 것</strong>(이슈 #48에서 추적):
 * <ul>
 *   <li>요청·응답 스키마의 property가 record component와 맞는지 — 필드를 추가·삭제·개명해도
 *       통과한다. 요청 본문은 "있는가 + media type이 비지 않았는가"까지만 본다.</li>
 *   <li>스키마의 {@code required}·{@code nullable} 제약이 실제 검증과 맞는지.</li>
 *   <li>계약 {@code enum}이 대응 Java enum 상수 집합과 맞는지.</li>
 *   <li>선언된 오류 응답(4xx·5xx)이 실제로 그 상태로 나오는지, 그리고 실제로 나는 오류 응답이
 *       전부 선언됐는지. security scheme을 참조하는 operation의 401 <em>존재</em>만 구조적으로
 *       요구할 뿐, 그 401이 실제로 나는지도, auth scheme을 선언하지 않은 경로의 filter-level
 *       401/403(예: 익명 전용 경로에 잘못된 Basic을 실은 경우)도 대조하지 않는다.</li>
 * </ul>
 * 이 목록을 줄이지 않은 채로 "계약 드리프트를 CI가 잡는다"고 서술하면 안 된다. 그 과신이 이번
 * 결손을 무증상으로 남긴 원인과 같은 종류다.
 *
 * <p>새 계약 파일을 게이트에 넣으려면 {@link #SPECS}에 파일 이름과 그 파일이 소유하는 경로
 * 접두사만 추가하면 된다. 검사 규칙은 파일마다 다시 쓰지 않는다.
 */
@SpringBootTest
@DisplayName("공개 OpenAPI 계약과 controller mapping 정합")
class PublicOpenApiContractTest {

	private static final Path CONTRACTS_DIRECTORY = Path.of("..", "contracts", "api");

	/**
	 * 게이트 대상 계약 파일과 그 파일이 소유하는 경로 접두사.
	 *
	 * <p>소유 접두사는 "이 파일이 그 경로 전부를 기술해야 한다"는 뜻이다. 접두사 안의 mapping이
	 * 계약에 없으면 실패하므로, 새 endpoint를 계약에 적지 않고 추가하는 것을 막는다.
	 */
	private static final List<SpecUnderTest> SPECS = List.of(
		new SpecUnderTest("realtime-api.openapi.yaml", List.of("/api/v1/realtime/")),
		new SpecUnderTest("report-api.openapi.yaml", List.of("/api/v1/report-uploads", "/api/v1/reports")),
		new SpecUnderTest("train-api.openapi.yaml", List.of("/api/v1/trains/"))
	);

	/**
	 * OpenAPI 3.0은 {@code Content-Type}·{@code Accept}·{@code Authorization} header parameter를
	 * 무시하도록 정하고 있다. 이 값들은 각각 requestBody content, responses content,
	 * securitySchemes로 기술해야 하므로 parameter 대조에서 제외한다.
	 */
	private static final Set<String> HEADERS_DESCRIBED_ELSEWHERE = Set.of(
		"content-type",
		"accept",
		"authorization"
	);

	private static final ParameterNameDiscoverer PARAMETER_NAMES = new DefaultParameterNameDiscoverer();

	@Autowired
	@Qualifier("requestMappingHandlerMapping")
	private RequestMappingHandlerMapping handlerMapping;

	@Test
	@DisplayName("계약에 선언된 operation은 실재하고, 계약이 소유한 경로에는 선언되지 않은 mapping이 없다")
	void declaredOperationsExistAndOwnedSurfaceIsFullyDeclared() {
		Map<OperationKey, HandlerMethod> handlers = handlers();
		List<String> violations = new ArrayList<>();

		for (SpecUnderTest spec : SPECS) {
			Map<String, Object> document = load(spec);
			Set<OperationKey> declared = new LinkedHashSet<>();
			for (Map.Entry<String, Object> pathEntry : paths(document).entrySet()) {
				for (String httpMethod : operations(pathEntry.getValue()).keySet()) {
					OperationKey key = new OperationKey(httpMethod, pathEntry.getKey());
					declared.add(key);
					if (!handlers.containsKey(key)) {
						violations.add(spec.fileName() + ": " + key + " 를 선언했지만 그런 controller mapping이 없다");
					}
				}
			}
			for (Map.Entry<OperationKey, HandlerMethod> handler : handlers.entrySet()) {
				OperationKey key = handler.getKey();
				if (!spec.owns(key.path()) || declared.contains(key)) {
					continue;
				}
				violations.add(spec.fileName() + ": " + key + " 가 계약에 없다"
					+ " (" + handler.getValue().getShortLogMessage() + ")");
			}
			for (String unmapped : pathsWithoutExplicitHttpMethod()) {
				if (spec.owns(unmapped)) {
					violations.add(spec.fileName() + ": " + unmapped
						+ " mapping이 HTTP method를 지정하지 않아 계약으로 기술할 수 없다");
				}
			}
		}

		assertThat(violations).as("계약 endpoint 정합").isEmpty();
	}

	@Test
	@DisplayName("controller가 요구하는 parameter·요청 본문이 계약에 그대로 선언돼 있다")
	void requestBindingsMatchDeclaredParametersAndBody() {
		Map<OperationKey, HandlerMethod> handlers = handlers();
		List<String> violations = new ArrayList<>();

		for (SpecUnderTest spec : SPECS) {
			forEachOperation(spec, handlers, (key, handler, operation) -> {
				List<Binding> bindings = bindings(handler);
				List<Map<String, Object>> declaredParameters = parameters(operation);

				for (Map<String, Object> parameter : declaredParameters) {
					String in = text(parameter.get("in"));
					String name = text(parameter.get("name"));
					if ("header".equals(in) && HEADERS_DESCRIBED_ELSEWHERE.contains(lower(name))) {
						violations.add(spec.fileName() + ": " + key + " 의 header parameter '" + name
							+ "' 는 OpenAPI가 무시하는 이름이다. requestBody·responses·securitySchemes로 기술한다");
						continue;
					}
					if (bindings.stream().noneMatch(binding -> binding.matches(in, name))) {
						violations.add(spec.fileName() + ": " + key + " 가 " + in + " parameter '" + name
							+ "' 를 선언했지만 controller는 받지 않는다");
					}
				}

				for (Binding binding : bindings) {
					if (!binding.required()) {
						// 선택 parameter의 선언 여부는 계약 저자의 판단으로 둔다. 서버가 거부하는
						// 요청을 만들어 내는 결손은 필수 parameter 누락 쪽이다.
						continue;
					}
					if ("header".equals(binding.in()) && HEADERS_DESCRIBED_ELSEWHERE.contains(lower(binding.name()))) {
						continue;
					}
					boolean declared = declaredParameters.stream()
						.anyMatch(parameter -> binding.matches(text(parameter.get("in")), text(parameter.get("name")))
							&& Boolean.TRUE.equals(parameter.get("required")));
					if (!declared) {
						violations.add(spec.fileName() + ": " + key + " 는 " + binding.in() + " parameter '"
							+ binding.name() + "' 를 반드시 요구하는데 계약에 required로 선언되지 않았다");
					}
				}

				boolean handlerReadsBody = readsRequestBody(handler);
				Object declaredBody = operation.get("requestBody");
				if (handlerReadsBody && declaredBody == null) {
					violations.add(spec.fileName() + ": " + key + " 는 요청 본문을 읽는데 계약에 requestBody가 없다");
				} else if (!handlerReadsBody && declaredBody != null) {
					violations.add(spec.fileName() + ": " + key + " 는 요청 본문을 읽지 않는데 계약에 requestBody가 있다");
				} else if (handlerReadsBody) {
					Map<String, Object> body = asMap(declaredBody);
					if (content(body).isEmpty()) {
						violations.add(spec.fileName() + ": " + key + " 의 requestBody에 media type이 없다");
					}
					if (requiresRequestBody(handler) && !Boolean.TRUE.equals(body.get("required"))) {
						violations.add(spec.fileName() + ": " + key
							+ " 는 요청 본문이 없으면 거부하는데 계약의 requestBody가 required가 아니다");
					}
				}
			}, violations);
		}

		assertThat(violations).as("요청 parameter·본문 정합").isEmpty();
	}

	@Test
	@DisplayName("성공 응답의 상태 코드와 본문 유무가 handler signature와 일치한다")
	void successResponsesMatchHandlerSignature() {
		Map<OperationKey, HandlerMethod> handlers = handlers();
		List<String> violations = new ArrayList<>();

		for (SpecUnderTest spec : SPECS) {
			forEachOperation(spec, handlers, (key, handler, operation) -> {
				String successStatus = successStatus(handler);
				Map<String, Object> responses = responses(operation);
				Object declared = responses.get(successStatus);
				if (declared == null) {
					violations.add(spec.fileName() + ": " + key + " 의 성공 응답 " + successStatus
						+ " 가 계약에 없다 (선언된 상태 코드: " + responses.keySet() + ")");
					return;
				}
				boolean hasBody = handler.getMethod().getReturnType() != void.class;
				boolean declaresContent = !content(asMap(declared)).isEmpty();
				if (hasBody && !declaresContent) {
					violations.add(spec.fileName() + ": " + key + " 는 응답 본문을 반환하는데 "
						+ successStatus + " 응답에 content가 없다");
				}
				if (!hasBody && declaresContent) {
					violations.add(spec.fileName() + ": " + key + " 는 응답 본문이 없는데 "
						+ successStatus + " 응답에 content가 있다");
				}
			}, violations);
		}

		assertThat(violations).as("성공 응답 정합").isEmpty();
	}

	@Test
	@DisplayName("security scheme을 참조하는 operation은 인증 실패 응답 401을 선언한다")
	void securedOperationsDeclareAuthFailureResponse() {
		// 이 저장소의 보호 경로는 httpBasic + 401 entry point를 쓴다. auth scheme을 제공하는
		// operation은 잘못된 자격 증명에 401을 반환하므로, 그 401을 계약이 빠뜨리면 생성
		// 클라이언트가 인증 실패를 모델링하지 못한다. 실제 리뷰에서 GET /reports/{reportId}가
		// 이 누락으로 걸렸다(#47). auth scheme을 선언하지 않는 operation의 filter-level 401
		// (예: 익명 전용 경로에 잘못된 Basic을 실은 경우)까지 전수 대조하는 것은 runtime
		// 검증이 필요해 #48 소관이다.
		Map<OperationKey, HandlerMethod> handlers = handlers();
		List<String> violations = new ArrayList<>();

		for (SpecUnderTest spec : SPECS) {
			forEachOperation(spec, handlers, (key, handler, operation) -> {
				if (!referencesSecurityScheme(operation)) {
					return;
				}
				if (!responses(operation).containsKey("401")) {
					violations.add(spec.fileName() + ": " + key
						+ " 는 security scheme을 참조하는데 인증 실패 응답 401을 선언하지 않았다"
						+ " (선언된 상태 코드: " + responses(operation).keySet() + ")");
				}
			}, violations);
		}

		assertThat(violations).as("security operation 인증 실패 응답").isEmpty();
	}

	@Test
	@DisplayName("계약이 선언한 component는 전부 참조되고, 모든 $ref는 실재하는 component를 가리킨다")
	void componentsAreAllReferencedAndResolvable() {
		List<String> violations = new ArrayList<>();

		for (SpecUnderTest spec : SPECS) {
			Map<String, Object> document = load(spec);
			Set<String> references = new LinkedHashSet<>();
			Set<String> securitySchemeUsages = new LinkedHashSet<>();
			collectReferences(document, references, securitySchemeUsages);

			Map<String, Object> components = asMap(document.get("components"));
			for (Map.Entry<String, Object> section : components.entrySet()) {
				for (String name : asMap(section.getValue()).keySet()) {
					if ("securitySchemes".equals(section.getKey())) {
						if (!securitySchemeUsages.contains(name)) {
							violations.add(spec.fileName() + ": securityScheme '" + name
								+ "' 가 선언만 되고 어떤 operation의 security에도 쓰이지 않는다");
						}
						continue;
					}
					String pointer = "#/components/" + section.getKey() + "/" + name;
					if (!references.contains(pointer)) {
						violations.add(spec.fileName() + ": " + pointer + " 가 선언만 되고 참조되지 않는다");
					}
				}
			}
			for (String reference : references) {
				if (!resolves(components, reference)) {
					violations.add(spec.fileName() + ": " + reference + " 가 실재하지 않는 component를 가리킨다");
				}
			}
		}

		assertThat(violations).as("component 참조 정합").isEmpty();
	}

	private void forEachOperation(
		SpecUnderTest spec,
		Map<OperationKey, HandlerMethod> handlers,
		OperationCheck check,
		List<String> violations
	) {
		Map<String, Object> document = load(spec);
		for (Map.Entry<String, Object> pathEntry : paths(document).entrySet()) {
			for (Map.Entry<String, Object> operationEntry : operations(pathEntry.getValue()).entrySet()) {
				OperationKey key = new OperationKey(operationEntry.getKey(), pathEntry.getKey());
				HandlerMethod handler = handlers.get(key);
				if (handler == null) {
					// endpoint 실재 여부는 declaredOperationsExistAndOwnedSurfaceIsFullyDeclared가 보고한다.
					continue;
				}
				check.accept(key, handler, asMap(operationEntry.getValue()));
			}
		}
	}

	private Map<OperationKey, HandlerMethod> handlers() {
		Map<OperationKey, HandlerMethod> byKey = new LinkedHashMap<>();
		for (Map.Entry<RequestMappingInfo, HandlerMethod> entry : handlerMapping.getHandlerMethods().entrySet()) {
			HandlerMethod handler = entry.getValue();
			if (!handler.getBeanType().getPackageName().startsWith("com.easysubway")) {
				continue;
			}
			for (String path : entry.getKey().getPatternValues()) {
				for (RequestMethod method : entry.getKey().getMethodsCondition().getMethods()) {
					byKey.put(new OperationKey(lower(method.name()), path), handler);
				}
			}
		}
		return byKey;
	}

	private Set<String> pathsWithoutExplicitHttpMethod() {
		Set<String> paths = new LinkedHashSet<>();
		for (Map.Entry<RequestMappingInfo, HandlerMethod> entry : handlerMapping.getHandlerMethods().entrySet()) {
			if (!entry.getValue().getBeanType().getPackageName().startsWith("com.easysubway")) {
				continue;
			}
			if (entry.getKey().getMethodsCondition().getMethods().isEmpty()) {
				paths.addAll(entry.getKey().getPatternValues());
			}
		}
		return paths;
	}

	private static List<Binding> bindings(HandlerMethod handler) {
		List<Binding> bindings = new ArrayList<>();
		for (MethodParameter parameter : handler.getMethodParameters()) {
			parameter.initParameterNameDiscovery(PARAMETER_NAMES);
			PathVariable pathVariable = parameter.getParameterAnnotation(PathVariable.class);
			if (pathVariable != null) {
				bindings.add(new Binding("path", declaredName(pathVariable.name(), parameter), pathVariable.required()));
				continue;
			}
			RequestParam requestParam = parameter.getParameterAnnotation(RequestParam.class);
			if (requestParam != null) {
				bindings.add(new Binding(
					"query",
					declaredName(requestParam.name(), parameter),
					requestParam.required() && !hasDefaultValue(requestParam.defaultValue())
				));
				continue;
			}
			RequestHeader requestHeader = parameter.getParameterAnnotation(RequestHeader.class);
			if (requestHeader != null) {
				bindings.add(new Binding(
					"header",
					declaredName(requestHeader.name(), parameter),
					requestHeader.required() && !hasDefaultValue(requestHeader.defaultValue())
				));
			}
		}
		return bindings;
	}

	private static boolean readsRequestBody(HandlerMethod handler) {
		return requestBodyAnnotation(handler) != null;
	}

	private static boolean requiresRequestBody(HandlerMethod handler) {
		RequestBody requestBody = requestBodyAnnotation(handler);
		return requestBody != null && requestBody.required();
	}

	private static RequestBody requestBodyAnnotation(HandlerMethod handler) {
		for (MethodParameter parameter : handler.getMethodParameters()) {
			RequestBody requestBody = parameter.getParameterAnnotation(RequestBody.class);
			if (requestBody != null) {
				return requestBody;
			}
		}
		return null;
	}

	private static String successStatus(HandlerMethod handler) {
		ResponseStatus responseStatus = AnnotatedElementUtils.findMergedAnnotation(
			handler.getMethod(),
			ResponseStatus.class
		);
		return responseStatus == null
			? "200"
			: String.valueOf(responseStatus.code().value());
	}

	private static String declaredName(String annotated, MethodParameter parameter) {
		if (annotated != null && !annotated.isBlank()) {
			return annotated;
		}
		String discovered = parameter.getParameterName();
		if (discovered == null) {
			throw new IllegalStateException(
				"parameter 이름을 읽지 못했다. javac -parameters 없이 빌드하면 이 게이트는 성립하지 않는다: "
					+ parameter.getExecutable()
			);
		}
		return discovered;
	}

	private static boolean hasDefaultValue(String defaultValue) {
		return !ValueConstants.DEFAULT_NONE.equals(defaultValue);
	}

	private Map<String, Object> load(SpecUnderTest spec) {
		Path path = CONTRACTS_DIRECTORY.resolve(spec.fileName());
		try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
			Map<String, Object> document = new Yaml().load(reader);
			assertThat(document).as(spec.fileName() + " 를 읽지 못했다").isNotNull();
			return document;
		} catch (java.io.IOException exception) {
			throw new IllegalStateException("계약 파일을 읽지 못했다: " + path, exception);
		}
	}

	private static Map<String, Object> paths(Map<String, Object> document) {
		return asMap(document.get("paths"));
	}

	/** path item에서 HTTP method key만 남긴다. {@code parameters}·{@code summary} 같은 형제 key는 제외한다. */
	private static Map<String, Object> operations(Object pathItem) {
		Map<String, Object> operations = new LinkedHashMap<>();
		for (Map.Entry<String, Object> entry : asMap(pathItem).entrySet()) {
			if (HTTP_METHODS.contains(lower(entry.getKey()))) {
				operations.put(lower(entry.getKey()), entry.getValue());
			}
		}
		return operations;
	}

	private static final Set<String> HTTP_METHODS = Set.of(
		"get", "put", "post", "delete", "options", "head", "patch", "trace"
	);

	@SuppressWarnings("unchecked")
	private static List<Map<String, Object>> parameters(Map<String, Object> operation) {
		Object parameters = operation.get("parameters");
		if (!(parameters instanceof List<?> list)) {
			return List.of();
		}
		List<Map<String, Object>> declared = new ArrayList<>();
		for (Object parameter : list) {
			if (parameter instanceof Map<?, ?> map) {
				declared.add((Map<String, Object>) map);
			}
		}
		return declared;
	}

	private static Map<String, Object> responses(Map<String, Object> operation) {
		Map<String, Object> responses = new LinkedHashMap<>();
		for (Map.Entry<String, Object> entry : asMap(operation.get("responses")).entrySet()) {
			responses.put(String.valueOf(entry.getKey()), entry.getValue());
		}
		return responses;
	}

	private static Map<String, Object> content(Map<String, Object> holder) {
		return asMap(holder.get("content"));
	}

	/**
	 * operation의 {@code security}에 비어 있지 않은 requirement가 하나라도 있는가.
	 * {@code [{}, {reportOwnerAuth: []}]}는 참(익명 허용 + auth scheme 제공),
	 * {@code []}·미선언은 거짓이다.
	 */
	private static boolean referencesSecurityScheme(Map<String, Object> operation) {
		Object security = operation.get("security");
		if (!(security instanceof List<?> requirements)) {
			return false;
		}
		return requirements.stream().anyMatch(requirement -> requirement instanceof Map<?, ?> map && !map.isEmpty());
	}

	@SuppressWarnings("unchecked")
	private static void collectReferences(Object node, Set<String> references, Set<String> securitySchemeUsages) {
		if (node instanceof Map<?, ?> map) {
			Object reference = map.get("$ref");
			if (reference instanceof String pointer) {
				references.add(pointer);
			}
			Object security = map.get("security");
			if (security instanceof List<?> requirements) {
				for (Object requirement : requirements) {
					if (requirement instanceof Map<?, ?> scheme) {
						scheme.keySet().forEach(key -> securitySchemeUsages.add(String.valueOf(key)));
					}
				}
			}
			for (Map.Entry<?, ?> entry : ((Map<Object, Object>) map).entrySet()) {
				if (!"security".equals(entry.getKey())) {
					collectReferences(entry.getValue(), references, securitySchemeUsages);
				}
			}
			return;
		}
		if (node instanceof Collection<?> collection) {
			collection.forEach(item -> collectReferences(item, references, securitySchemeUsages));
		}
	}

	private static boolean resolves(Map<String, Object> components, String reference) {
		String[] segments = reference.split("/");
		if (segments.length != 4 || !"#".equals(segments[0]) || !"components".equals(segments[1])) {
			return false;
		}
		return asMap(components.get(segments[2])).containsKey(segments[3]);
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> asMap(Object node) {
		return node instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
	}

	private static String text(Object value) {
		return value == null ? "" : String.valueOf(value);
	}

	private static String lower(String value) {
		return value.toLowerCase(Locale.ROOT);
	}

	private record SpecUnderTest(String fileName, List<String> ownedPathPrefixes) {

		boolean owns(String path) {
			return ownedPathPrefixes.stream().anyMatch(path::startsWith);
		}
	}

	private record OperationKey(String httpMethod, String path) {

		@Override
		public String toString() {
			return httpMethod.toUpperCase(Locale.ROOT) + " " + path;
		}
	}

	/** header 이름은 HTTP 규칙대로 대소문자를 구분하지 않고 대조한다. */
	private record Binding(String in, String name, boolean required) {

		boolean matches(String otherIn, String otherName) {
			if (!in.equals(otherIn)) {
				return false;
			}
			return "header".equals(in) ? name.equalsIgnoreCase(otherName) : name.equals(otherName);
		}
	}

	@FunctionalInterface
	private interface OperationCheck {

		void accept(OperationKey key, HandlerMethod handler, Map<String, Object> operation);
	}
}
