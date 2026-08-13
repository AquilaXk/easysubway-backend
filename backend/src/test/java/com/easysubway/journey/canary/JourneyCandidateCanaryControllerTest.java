package com.easysubway.journey.canary;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.easysubway.journey.adapter.in.web.JourneyCandidateCanaryController;
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
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class JourneyCandidateCanaryControllerTest {

	private final JourneyCandidateCanaryService service = mock(JourneyCandidateCanaryService.class);
	private JourneyCandidateCanaryController controller;
	private MockMvc mockMvc;

	@BeforeEach
	void setUp() {
		controller = new JourneyCandidateCanaryController(new JourneyCandidateCanaryCommandParser(), service);
		mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
	}

	@Test
	void validCommandReturnsTheExactClosedNoFallbackEvidence() throws Exception {
		when(service.execute(any())).thenReturn(result());

		mockMvc.perform(validRequest())
			.andExpect(status().isOk())
			.andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
			.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
			.andExpect(jsonPath("$.schemaVersion").value(1))
			.andExpect(jsonPath("$.artifactKind").value("journey-v3-candidate-canary-result"))
			.andExpect(jsonPath("$.canaryRequestIdentity").value("canary-request-236"))
			.andExpect(jsonPath("$.candidateManifestSha256").value(JourneyCandidateCanaryCommandParserTest.SHA_A))
			.andExpect(jsonPath("$.candidateGeneration").value(1))
			.andExpect(jsonPath("$.passed").value(true))
			.andExpect(jsonPath("$.legacyGraphSuccessCount").value(0))
			.andExpect(jsonPath("$.localRouteInvocationCount").value(0))
			.andExpect(jsonPath("$.staleJourneyServedCount").value(0))
			.andExpect(jsonPath("$.alternateEndpointSuccessCount").value(0))
			.andExpect(jsonPath("$.evidenceSha256").value(org.hamcrest.Matchers.matchesPattern("[0-9a-f]{64}")))
			.andExpect(jsonPath("$.detail").doesNotExist());
	}

	@Test
	void missingMalformedWrongAndWildcardContentTypesAreSanitizedBadRequests() throws Exception {
		mockMvc.perform(post(JourneyCandidateCanaryController.PATH)
				.content(JourneyCandidateCanaryCommandParserTest.validCommand()))
			.andExpect(status().isBadRequest())
			.andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
			.andExpect(jsonPath("$.reason").value("INVALID_REQUEST"));

		mockMvc.perform(post(JourneyCandidateCanaryController.PATH)
				.contentType(MediaType.APPLICATION_JSON).content("{"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.artifactKind").value("journey-v3-candidate-canary-failure"))
			.andExpect(jsonPath("$.passed").value(false));

		mockMvc.perform(post(JourneyCandidateCanaryController.PATH)
				.contentType(MediaType.TEXT_PLAIN).content(JourneyCandidateCanaryCommandParserTest.validCommand()))
			.andExpect(status().isBadRequest());

		for (String contentType : new String[] {
			"*/*", "application/*", "application/*+json", "application/problem+json"}) {
			mockMvc.perform(post(JourneyCandidateCanaryController.PATH)
					.header(HttpHeaders.CONTENT_TYPE, contentType)
					.content(JourneyCandidateCanaryCommandParserTest.validCommand()))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.reason").value("INVALID_REQUEST"));
		}
		verifyNoInteractions(service);
	}

	@Test
	void conflictAndUnavailableUseTheExactFourFieldFailureSchema() throws Exception {
		when(service.execute(any()))
			.thenThrow(new JourneyCandidateCanaryException(JourneyCandidateCanaryException.Kind.CONFLICT))
			.thenThrow(new JourneyCandidateCanaryException(JourneyCandidateCanaryException.Kind.UNAVAILABLE));

		mockMvc.perform(validRequest())
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$").isMap())
			.andExpect(jsonPath("$.schemaVersion").value(1))
			.andExpect(jsonPath("$.artifactKind").value("journey-v3-candidate-canary-failure"))
			.andExpect(jsonPath("$.passed").value(false))
			.andExpect(jsonPath("$.reason").value("CONFLICT"))
			.andExpect(jsonPath("$.detail").doesNotExist());
		mockMvc.perform(validRequest())
			.andExpect(status().isServiceUnavailable())
			.andExpect(jsonPath("$.reason").value("UNAVAILABLE"));
	}

	@Test
	void invalidMediaTypeAndBodyReadFailureAreSanitized() throws Exception {
		var invalidMediaType = mock(HttpServletRequest.class);
		when(invalidMediaType.getContentType()).thenReturn("not a media type;;;");
		ResponseEntity<?> invalidMediaTypeResponse = ReflectionTestUtils.invokeMethod(
			controller, "canary", invalidMediaType);
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
		ResponseEntity<?> readFailureResponse = ReflectionTestUtils.invokeMethod(controller, "canary", readFailure);
		assertThat(readFailureResponse.getStatusCode().value()).isEqualTo(400);
	}

	private static org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder validRequest() {
		return post(JourneyCandidateCanaryController.PATH)
			.contentType(MediaType.APPLICATION_JSON)
			.content(JourneyCandidateCanaryCommandParserTest.validCommand());
	}

	private static JourneyCandidateCanaryService.Result result() {
		return new JourneyCandidateCanaryService.Result(
			1, "journey-v3-candidate-canary-result", "canary-request-236",
			JourneyCandidateCanaryCommandParserTest.REQUEST_ID,
			JourneyCandidateCanaryCommandParserTest.SHA_A, 1, "bundle-a", 31,
			JourneyCandidateCanaryCommandParserTest.REQUEST_ID, Instant.parse("2026-08-13T03:00:00Z"),
			true, 0, 0, 0, 0, "f".repeat(64));
	}
}
