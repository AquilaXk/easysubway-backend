package com.easysubway.journey.activation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.easysubway.journey.adapter.in.web.JourneyActivationController;
import com.easysubway.journey.readiness.JourneyReadinessService;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.test.util.ReflectionTestUtils;

class JourneyActivationControllerTest {

	private final JourneyActivationService activationService = mock(JourneyActivationService.class);
	private JourneyActivationController controller;
	private MockMvc mockMvc;

	@BeforeEach
	void setUp() {
		controller = new JourneyActivationController(new JourneyActivationCommandParser(), activationService);
		mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
	}

	@Test
	void validCommandReturnsTheExactExistingActiveReadiness() throws Exception {
		when(activationService.activate(any())).thenReturn(activeReadiness());

		mockMvc.perform(post(JourneyActivationController.PATH)
				.contentType(MediaType.APPLICATION_JSON)
				.content(JourneyActivationCommandParserTest.validCommand()))
			.andExpect(status().isOk())
			.andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
			.andExpect(jsonPath("$.artifactKind").value("journey-v3-active-readiness"))
			.andExpect(jsonPath("$.generation").value(1))
			.andExpect(jsonPath("$.trafficGeneration").value(31))
			.andExpect(jsonPath("$.servingReady").value(true));
	}

	@Test
	void missingContentTypeAndMalformedBodyAreSanitizedBadRequests() throws Exception {
		mockMvc.perform(post(JourneyActivationController.PATH)
				.content(JourneyActivationCommandParserTest.validCommand()))
			.andExpect(status().isBadRequest())
			.andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
			.andExpect(jsonPath("$.reason").value("INVALID_REQUEST"));

		mockMvc.perform(post(JourneyActivationController.PATH)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.artifactKind").value("journey-v3-activation-failure"))
			.andExpect(jsonPath("$.activated").value(false))
			.andExpect(jsonPath("$.detail").doesNotExist());

		mockMvc.perform(post(JourneyActivationController.PATH)
				.contentType(MediaType.TEXT_PLAIN)
				.content(JourneyActivationCommandParserTest.validCommand()))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.reason").value("INVALID_REQUEST"));
	}

	@Test
	void conflictAndUnavailableFailuresUseTheClosedSchema() throws Exception {
		when(activationService.activate(any()))
			.thenThrow(new JourneyActivationException(JourneyActivationException.Kind.CONFLICT))
			.thenThrow(new JourneyActivationException(JourneyActivationException.Kind.UNAVAILABLE));

		mockMvc.perform(validRequest())
			.andExpect(status().isConflict())
			.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
			.andExpect(jsonPath("$.reason").value("CONFLICT"));
		mockMvc.perform(validRequest())
			.andExpect(status().isServiceUnavailable())
			.andExpect(jsonPath("$.reason").value("UNAVAILABLE"));
	}

	@Test
	void invalidMediaTypeAndBodyReadFailureAreSanitized() throws Exception {
		var invalidMediaType = mock(HttpServletRequest.class);
		when(invalidMediaType.getContentType()).thenReturn("not a media type;;;");
		ResponseEntity<?> invalidMediaTypeResponse = ReflectionTestUtils.invokeMethod(
			controller, "activate", invalidMediaType);
		assertThat(invalidMediaTypeResponse.getStatusCode().value()).isEqualTo(400);

		var readFailure = mock(HttpServletRequest.class);
		when(readFailure.getContentType()).thenReturn(MediaType.APPLICATION_JSON_VALUE);
		var input = new ServletInputStream() {
			@Override
			public int read() throws IOException {
				throw new IOException("synthetic read failure");
			}

			@Override
			public boolean isFinished() {
				return false;
			}

			@Override
			public boolean isReady() {
				return true;
			}

			@Override
			public void setReadListener(ReadListener readListener) {
			}
		};
		when(readFailure.getInputStream()).thenReturn(input);
		ResponseEntity<?> readFailureResponse = ReflectionTestUtils.invokeMethod(
			controller, "activate", readFailure);
		assertThat(readFailureResponse.getStatusCode().value()).isEqualTo(400);
	}

	private static org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder validRequest() {
		return post(JourneyActivationController.PATH)
			.contentType(MediaType.APPLICATION_JSON)
			.content(JourneyActivationCommandParserTest.validCommand());
	}

	private static JourneyReadinessService.ActiveReadiness activeReadiness() {
		String sha = "a".repeat(64);
		return new JourneyReadinessService.ActiveReadiness(
			1, "journey-v3-active-readiness", "backend-a", sha, "sha256:" + sha, sha, sha,
			sha, "bundle-a", 1, 1, 31, true, false,
			Instant.parse("2026-08-14T00:00:00Z"), Instant.parse("2026-08-13T00:00:00Z"), sha);
	}
}
