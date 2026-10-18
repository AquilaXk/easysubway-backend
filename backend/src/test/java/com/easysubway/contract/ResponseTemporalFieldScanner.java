package com.easysubway.contract;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.springframework.core.ResolvableType;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * JSON 본문으로 나가는 controller 응답 타입을 따라가며 시각 타입 필드를 모은다.
 *
 * <p>controller 메서드의 반환 타입을 제네릭까지 풀어 record 구성요소와 필드를 재귀로 따라간다.
 * 반환 타입이 {@code Map<String, Object>}·{@code JsonNode}처럼 필드를 드러내지 않으면 따라갈 수 없으므로
 * 그런 엔드포인트는 {@link #untypedBodies()}로 따로 보고한다. 이 목록은 사람이 직접 확인해야 한다.
 */
final class ResponseTemporalFieldScanner {

	private static final String OWN_PACKAGE_PREFIX = "com.easysubway.";
	private static final Set<Class<?>> TEMPORAL_TYPES = Set.of(
		LocalDateTime.class, LocalDate.class, LocalTime.class,
		Instant.class, OffsetDateTime.class, ZonedDateTime.class,
		java.util.Date.class, java.sql.Timestamp.class);

	private final Map<String, Class<?>> temporalFields = new TreeMap<>();
	private final Set<String> untypedBodies = new TreeSet<>();
	private final Set<String> visitedTypes = new HashSet<>();

	static ResponseTemporalFieldScanner scan(String basePackage) {
		ResponseTemporalFieldScanner scanner = new ResponseTemporalFieldScanner();
		for (Class<?> controller : controllers(basePackage)) {
			for (Method method : controller.getDeclaredMethods()) {
				if (isJsonEndpoint(controller, method)) {
					String endpoint = controller.getSimpleName() + "#" + method.getName();
					scanner.follow(ResolvableType.forMethodReturnType(method, controller), endpoint, endpoint);
				}
			}
		}
		return scanner;
	}

	/** 키는 {@code 선언클래스.필드}, 값은 시각 타입이다. */
	Map<String, Class<?>> temporalFields() {
		return temporalFields;
	}

	/** 본문 구조를 따라갈 수 없어 사람이 확인해야 하는 엔드포인트. */
	Set<String> untypedBodies() {
		return untypedBodies;
	}

	/** {@code @Profile} 조건과 무관하게 모든 controller를 모은다. 운영 전용 controller도 계약 대상이다. */
	private static List<Class<?>> controllers(String basePackage) {
		List<Class<?>> classes = new ArrayList<>();
		for (JavaClass javaClass : new ClassFileImporter()
			.withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
			.importPackages(basePackage)) {
			if (javaClass.isMetaAnnotatedWith(Controller.class)) {
				classes.add(javaClass.reflect());
			}
		}
		return classes;
	}

	private static boolean isJsonEndpoint(Class<?> controller, Method method) {
		return AnnotatedElementUtils.hasAnnotation(method, RequestMapping.class)
			&& (AnnotatedElementUtils.hasAnnotation(controller, RestController.class)
				|| AnnotatedElementUtils.hasAnnotation(method, ResponseBody.class));
	}

	private void follow(ResolvableType type, String endpoint, String path) {
		Class<?> raw = type.resolve(Object.class);
		if (TEMPORAL_TYPES.contains(raw)) {
			temporalFields.put(path, raw);
		} else if (raw.isArray()) {
			follow(type.getComponentType(), endpoint, path);
		} else if (Map.class.isAssignableFrom(raw)) {
			follow(type.asMap().getGeneric(1), endpoint, path);
		} else if (isWrapper(raw)) {
			follow(type.getGeneric(0), endpoint, path);
		} else if (isOpaque(raw)) {
			untypedBodies.add(endpoint + " -> " + type);
		} else if (raw.getName().startsWith(OWN_PACKAGE_PREFIX) && visitedTypes.add(type.toString())) {
			followFields(type, raw, endpoint);
		}
	}

	private void followFields(ResolvableType type, Class<?> raw, String endpoint) {
		for (Field field : raw.getDeclaredFields()) {
			if (!Modifier.isStatic(field.getModifiers())) {
				String path = raw.getName().substring(OWN_PACKAGE_PREFIX.length()) + "." + field.getName();
				follow(ResolvableType.forField(field, type), endpoint, path);
			}
		}
	}

	private static boolean isWrapper(Class<?> raw) {
		return Collection.class.isAssignableFrom(raw) || Optional.class.isAssignableFrom(raw)
			|| ResponseEntity.class.isAssignableFrom(raw);
	}

	/** 필드를 드러내지 않는 타입: 타입 없는 Object, Jackson 트리, 와일드카드 응답. */
	private static boolean isOpaque(Class<?> raw) {
		return raw == Object.class || raw.getName().startsWith("com.fasterxml.jackson.databind.");
	}
}
