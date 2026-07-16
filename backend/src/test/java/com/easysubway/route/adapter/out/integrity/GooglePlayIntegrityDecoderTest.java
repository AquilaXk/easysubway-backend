package com.easysubway.route.adapter.out.integrity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.easysubway.route.application.port.out.PlayIntegrityProviderUnavailableException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestClient;

@DisplayName("Google Play Integrity decode adapter")
class GooglePlayIntegrityDecoderTest {

	@Test
	@DisplayName("전용 RestClient clone에 bounded connect/read timeout을 설정한다")
	void configuresBoundedTimeoutsOnDedicatedClient() {
		RestClient.Builder sharedBuilder = mock(RestClient.Builder.class);
		RestClient.Builder dedicatedBuilder = mock(RestClient.Builder.class);
		when(sharedBuilder.clone()).thenReturn(dedicatedBuilder);
		when(dedicatedBuilder.requestFactory(any())).thenReturn(dedicatedBuilder);
		when(dedicatedBuilder.build()).thenReturn(mock(RestClient.class));

		new GooglePlayIntegrityDecoder(
			sharedBuilder,
			new ObjectMapper(),
			""
		);

		ArgumentCaptor<SimpleClientHttpRequestFactory> factoryCaptor =
			ArgumentCaptor.forClass(SimpleClientHttpRequestFactory.class);
		verify(sharedBuilder).clone();
		verify(dedicatedBuilder).requestFactory(factoryCaptor.capture());
		assertThat(ReflectionTestUtils.getField(factoryCaptor.getValue(), "connectTimeout")).isEqualTo(3_000);
		assertThat(ReflectionTestUtils.getField(factoryCaptor.getValue(), "readTimeout")).isEqualTo(5_000);
	}

	@Test
	@DisplayName("공식 decode endpoint 응답에서 검증 대상 verdict만 추출한다")
	void decodesOfficialTokenPayload() {
		RestClient.Builder builder = RestClient.builder();
		MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
		server.expect(requestTo(
			"https://playintegrity.googleapis.com/v1/com.easysubway.app:decodeIntegrityToken"
		))
			.andExpect(header("Authorization", "Bearer google-access-token"))
			.andExpect(content().json("{\"integrityToken\":\"integrity-token\"}"))
			.andRespond(withSuccess("""
				{
				  "tokenPayloadExternal": {
				    "requestDetails": {
				      "requestPackageName": "com.easysubway.app",
				      "requestHash": "request-hash",
				      "timestampMillis": "1784192400000"
				    },
				    "appIntegrity": {
				      "packageName": "com.easysubway.app",
				      "appRecognitionVerdict": "PLAY_RECOGNIZED",
				      "certificateSha256Digest": ["certificate-digest"]
				    },
				    "accountDetails": {"appLicensingVerdict": "LICENSED"},
				    "deviceIntegrity": {
				      "deviceRecognitionVerdict": ["MEETS_DEVICE_INTEGRITY"]
				    }
				  }
				}
				""", MediaType.APPLICATION_JSON));
		var decoder = new GooglePlayIntegrityDecoder(
			builder.build(),
			new ObjectMapper(),
			() -> "google-access-token"
		);

		var verdict = decoder.decode("integrity-token");

		assertThat(verdict.requestPackageName()).isEqualTo("com.easysubway.app");
		assertThat(verdict.requestHash()).isEqualTo("request-hash");
		assertThat(verdict.requestTimestamp()).isEqualTo("2026-07-16T09:00:00Z");
		assertThat(verdict.appPackageName()).isEqualTo("com.easysubway.app");
		assertThat(verdict.appRecognitionVerdict()).isEqualTo("PLAY_RECOGNIZED");
		assertThat(verdict.certificateSha256Digests()).containsExactly("certificate-digest");
		assertThat(verdict.appLicensingVerdict()).isEqualTo("LICENSED");
		assertThat(verdict.deviceRecognitionVerdicts()).containsExactly("MEETS_DEVICE_INTEGRITY");
		server.verify();
	}

	@Test
	@DisplayName("invalid token 400은 attestation 거부용 빈 verdict로 변환한다")
	void mapsInvalidTokenToRejectedVerdict() {
		RestClient.Builder builder = RestClient.builder();
		MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
		server.expect(requestTo("https://playintegrity.googleapis.com/v1/com.easysubway.app:decodeIntegrityToken"))
			.andRespond(withStatus(HttpStatus.BAD_REQUEST));
		var decoder = new GooglePlayIntegrityDecoder(builder.build(), new ObjectMapper(), () -> "google-access-token");

		assertThat(decoder.decode("invalid-token").requestPackageName()).isNull();
		server.verify();
	}

	@Test
	@DisplayName("provider 인증·권한 장애는 invalid token과 구분한다")
	void reportsProviderUnavailability() {
		RestClient.Builder builder = RestClient.builder();
		MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
		server.expect(requestTo("https://playintegrity.googleapis.com/v1/com.easysubway.app:decodeIntegrityToken"))
			.andRespond(withStatus(HttpStatus.FORBIDDEN));
		var decoder = new GooglePlayIntegrityDecoder(builder.build(), new ObjectMapper(), () -> "google-access-token");

		assertThatThrownBy(() -> decoder.decode("integrity-token"))
			.isInstanceOf(PlayIntegrityProviderUnavailableException.class);
		server.verify();
	}
}
