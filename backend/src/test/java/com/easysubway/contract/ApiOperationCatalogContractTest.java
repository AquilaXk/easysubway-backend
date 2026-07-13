package com.easysubway.contract;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

@SpringBootTest
@DisplayName("프로젝트 HTTP API catalog 계약")
class ApiOperationCatalogContractTest {

	private static final Path CATALOG_PATH = Path.of("..", "contracts", "api", "internal-api-index.json");

	@Autowired
	@Qualifier("requestMappingHandlerMapping")
	private RequestMappingHandlerMapping handlerMapping;

	@Test
	@DisplayName("EasySubway controller mapping은 deterministic index와 일치하고 catalog endpoint를 노출하지 않는다")
	void mappingsMatchTrackedCatalogWithoutRuntimeCatalogEndpoint() throws Exception {
		ApiCatalog catalog = buildCatalog(handlerMapping.getHandlerMethods());
		assertThat(catalog.operations())
			.extracting(ApiOperation::path)
			.noneMatch(path -> path.matches("(?:.*/)?api/catalog(?:/.*)?")
				|| path.contains("/api-catalog"));
		assertThat(catalog.operations())
			.filteredOn(operation -> operation.handlerClass().endsWith("DataCollectionController")
				|| operation.handlerClass().endsWith("DataQualityController")
				|| operation.handlerClass().endsWith("RealtimeProviderAdminController")
				|| operation.handlerClass().endsWith("UserActivityAdminApiController"))
			.allMatch(operation -> operation.surface().equals("ADMIN_API"));

		String actual = catalogJson(catalog, new ObjectMapper());
		if ("1".equals(System.getenv("EASYSUBWAY_API_CATALOG_WRITE"))) {
			Files.createDirectories(CATALOG_PATH.getParent());
			Files.writeString(CATALOG_PATH, actual);
		}

		assertThat(CATALOG_PATH).exists();
		assertThat(Files.readString(CATALOG_PATH)).isEqualTo(actual);
	}

	private static String catalogJson(ApiCatalog catalog, ObjectMapper mapper) throws Exception {
		StringBuilder json = new StringBuilder()
			.append("{\n  \"schemaVersion\": ").append(catalog.schemaVersion())
			.append(",\n  \"generatedBy\": ").append(mapper.writeValueAsString(catalog.generatedBy()))
			.append(",\n  \"operations\": [\n");
		for (int index = 0; index < catalog.operations().size(); index++) {
			json.append("    ").append(mapper.writeValueAsString(catalog.operations().get(index)));
			if (index + 1 < catalog.operations().size()) json.append(',');
			json.append('\n');
		}
		return json.append("  ]\n}\n").toString();
	}

	private static ApiCatalog buildCatalog(
		java.util.Map<RequestMappingInfo, HandlerMethod> handlerMethods
	) {
		List<ApiOperation> operations = new ArrayList<>();
		for (var entry : handlerMethods.entrySet()) {
			HandlerMethod handler = entry.getValue();
			if (!handler.getBeanType().getPackageName().startsWith("com.easysubway")) {
				continue;
			}
			RequestMappingInfo mapping = entry.getKey();
			Set<RequestMethod> mappedMethods = mapping.getMethodsCondition().getMethods();
			List<String> methods = mappedMethods.isEmpty()
				? List.of("ANY")
				: mappedMethods.stream().map(Enum::name).sorted().toList();
			for (String path : mapping.getPatternValues().stream().sorted().toList()) {
				for (String method : methods) {
					String handlerClass = handler.getBeanType().getName();
					String javaMethod = handler.getMethod().getName();
					operations.add(new ApiOperation(
						"internal:" + method + ":" + path + ":" + handlerClass + "#" + javaMethod,
						method,
						path,
						classify(path, handler.getBeanType()),
						handlerClass,
						javaMethod
					));
				}
			}
		}
		operations.sort(Comparator.comparing(ApiOperation::path)
			.thenComparing(ApiOperation::method)
			.thenComparing(ApiOperation::handlerClass)
			.thenComparing(ApiOperation::javaMethod));
		return new ApiCatalog(1, "Spring RequestMappingHandlerMapping", List.copyOf(operations));
	}

	private static String classify(String path, Class<?> handlerType) {
		boolean restController = AnnotatedElementUtils.hasAnnotation(handlerType, RestController.class);
		if (path.startsWith("/admin/api/") || (restController && path.startsWith("/admin/"))) return "ADMIN_API";
		if (path.startsWith("/operator/api/") || (restController && path.startsWith("/operator/"))) return "OPERATOR_API";
		if (path.startsWith("/api/") || restController) return "PUBLIC_API";
		return "PAGE";
	}

	private record ApiCatalog(int schemaVersion, String generatedBy, List<ApiOperation> operations) {}

	private record ApiOperation(
		String id,
		String method,
		String path,
		String surface,
		String handlerClass,
		String javaMethod
	) {}
}
