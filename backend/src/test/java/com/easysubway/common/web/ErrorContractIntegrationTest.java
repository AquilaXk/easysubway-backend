package com.easysubway.common.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.easysubway.common.error.ConflictException;
import com.easysubway.common.error.CorrelationId;
import com.easysubway.common.error.ResourceNotFoundException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@DisplayName("에러 코드 계약")
class ErrorContractIntegrationTest {

	private MockMvc mockMvc;

	@BeforeEach
	void setUp() {
		mockMvc = MockMvcBuilders.standaloneSetup(new ErrorContractProbeController())
			.setControllerAdvice(new CommonExceptionHandler(WebMessageResolver.defaultMessages()))
			.addFilters(new CorrelationIdFilter())
			.build();
	}

	@Test
	@DisplayName("잘못된 바디 400 응답에 code·correlationId·한국어 message를 싣는다")
	void unreadableBodyIncludesErrorContract() throws Exception {
		MvcResult result = mockMvc.perform(post("/api/test/error-contract/echo")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{"))
			.andExpect(status().isBadRequest())
			.andExpect(header().exists(CorrelationId.HEADER))
			.andExpect(jsonPath("$.success").value(false))
			.andExpect(jsonPath("$.code").value("UNREADABLE_BODY"))
			.andExpect(jsonPath("$.message").value("요청 본문을 확인해야 합니다."))
			.andExpect(jsonPath("$.correlationId").isString())
			.andReturn();

		assertThat(result.getResponse().getHeader(CorrelationId.HEADER))
			.isEqualTo(jsonPathString(result, "$.correlationId"));
	}

	@Test
	@DisplayName("404 응답에 code·correlationId·한국어 message를 싣는다")
	void notFoundIncludesErrorContract() throws Exception {
		mockMvc.perform(get("/api/test/error-contract/not-found"))
			.andExpect(status().isNotFound())
			.andExpect(header().exists(CorrelationId.HEADER))
			.andExpect(jsonPath("$.success").value(false))
			.andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"))
			.andExpect(jsonPath("$.message").value("대상을 찾을 수 없습니다."))
			.andExpect(jsonPath("$.correlationId").isString());
	}

	@Test
	@DisplayName("409 응답에 code·correlationId·한국어 message를 싣는다")
	void conflictIncludesErrorContract() throws Exception {
		mockMvc.perform(get("/api/test/error-contract/conflict"))
			.andExpect(status().isConflict())
			.andExpect(header().exists(CorrelationId.HEADER))
			.andExpect(jsonPath("$.success").value(false))
			.andExpect(jsonPath("$.code").value("CONFLICT"))
			.andExpect(jsonPath("$.message").value("이미 처리된 요청입니다."))
			.andExpect(jsonPath("$.correlationId").isString());
	}

	@Test
	@DisplayName("미처리 RuntimeException은 ApiResponse 형식 500 INTERNAL_ERROR로 응답한다")
	void unhandledExceptionReturnsInternalErrorContract() throws Exception {
		mockMvc.perform(get("/api/test/error-contract/boom"))
			.andExpect(status().isInternalServerError())
			.andExpect(header().exists(CorrelationId.HEADER))
			.andExpect(jsonPath("$.success").value(false))
			.andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
			.andExpect(jsonPath("$.message").value("일시적인 문제가 발생했어요. 잠시 후 다시 시도해 주세요."))
			.andExpect(jsonPath("$.correlationId").isString())
			.andExpect(jsonPath("$.data").doesNotExist())
			.andExpect(jsonPath("$..secret-internal").doesNotExist());
	}

	@Test
	@DisplayName("401 ResponseStatusException은 잘못된 code 없이 빈 바디로 유지한다")
	void unauthorizedResponseStatusKeepsEmptyBody() throws Exception {
		MvcResult result = mockMvc.perform(get("/api/test/error-contract/unauthorized"))
			.andExpect(status().isUnauthorized())
			.andExpect(header().exists(CorrelationId.HEADER))
			.andReturn();

		assertThat(result.getResponse().getContentAsString()).isBlank();
	}

	@Test
	@DisplayName("허용되지 않은 HTTP 메서드는 500 INTERNAL_ERROR가 아닌 405로 응답한다")
	void methodNotAllowedIsNotInternalError() throws Exception {
		MvcResult result = mockMvc.perform(post("/api/test/error-contract/ok")
				.contentType(MediaType.APPLICATION_JSON))
			.andExpect(status().isMethodNotAllowed())
			.andReturn();

		String body = result.getResponse().getContentAsString();
		assertThat(body).doesNotContain("INTERNAL_ERROR");
		assertThat(result.getResponse().getStatus()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED.value());
	}

	@Test
	@DisplayName("성공 응답 JSON에 code·correlationId 필드가 나타나지 않는다")
	void successOmitsErrorFields() throws Exception {
		String body = mockMvc.perform(get("/api/test/error-contract/ok"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.success").value(true))
			.andExpect(jsonPath("$.data").value("fine"))
			.andExpect(jsonPath("$.code").doesNotExist())
			.andExpect(jsonPath("$.correlationId").doesNotExist())
			.andReturn()
			.getResponse()
			.getContentAsString();

		assertThat(body).doesNotContain("\"code\"").doesNotContain("\"correlationId\"");
		assertThat(new ObjectMapper().readTree(body).has("code")).isFalse();
		assertThat(new ObjectMapper().readTree(body).has("correlationId")).isFalse();
	}

	private static String jsonPathString(MvcResult result, String path) throws Exception {
		return com.jayway.jsonpath.JsonPath.read(result.getResponse().getContentAsString(), path);
	}

	@RestController
	static class ErrorContractProbeController {

		@PostMapping("/api/test/error-contract/echo")
		ApiResponse<Map<String, Object>> echo(@RequestBody Map<String, Object> body) {
			return ApiResponse.ok(body);
		}

		@GetMapping("/api/test/error-contract/not-found")
		ApiResponse<Void> notFound() {
			throw new ResourceNotFoundException("대상을 찾을 수 없습니다.");
		}

		@GetMapping("/api/test/error-contract/conflict")
		ApiResponse<Void> conflict() {
			throw new ConflictException("이미 처리된 요청입니다.");
		}

		@GetMapping("/api/test/error-contract/boom")
		ApiResponse<Void> boom() {
			throw new RuntimeException("secret-internal");
		}

		@GetMapping("/api/test/error-contract/unauthorized")
		ApiResponse<Void> unauthorized() {
			throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
		}

		@GetMapping("/api/test/error-contract/ok")
		ApiResponse<String> ok() {
			return ApiResponse.ok("fine");
		}
	}
}
