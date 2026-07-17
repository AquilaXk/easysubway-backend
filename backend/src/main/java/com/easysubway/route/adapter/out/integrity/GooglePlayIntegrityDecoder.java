package com.easysubway.route.adapter.out.integrity;

import com.easysubway.route.application.port.out.PlayIntegrityDecoder;
import com.easysubway.route.application.port.out.PlayIntegrityProviderUnavailableException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.api.client.http.BoundedHttpTransport;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.auth.oauth2.AccessToken;
import com.google.auth.oauth2.GoogleCredentials;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

@Component
@Profile("(prod | staging | release | prod-like) & !capacity-evidence")
public class GooglePlayIntegrityDecoder implements PlayIntegrityDecoder {

	private static final String DECODE_URL =
		"https://playintegrity.googleapis.com/v1/com.easysubway.app:decodeIntegrityToken";
	private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(3);
	private static final Duration READ_TIMEOUT = Duration.ofSeconds(5);
	private static final String PLAY_INTEGRITY_SCOPE = "https://www.googleapis.com/auth/playintegrity";

	private final RestClient restClient;
	private final ObjectMapper objectMapper;
	private final AccessTokenProvider accessTokenProvider;

	@Autowired
	public GooglePlayIntegrityDecoder(
		RestClient.Builder restClientBuilder,
		ObjectMapper objectMapper,
		@Value("${easysubway.play-integrity.credentials-base64:}") String credentialsBase64
	) {
		this(
			boundedRestClient(restClientBuilder),
			objectMapper,
			new GoogleAccessTokenProvider(credentialsBase64)
		);
	}

	GooglePlayIntegrityDecoder(
		RestClient restClient,
		ObjectMapper objectMapper,
		AccessTokenProvider accessTokenProvider
	) {
		this.restClient = restClient;
		this.objectMapper = objectMapper;
		this.accessTokenProvider = accessTokenProvider;
	}

	private static RestClient boundedRestClient(RestClient.Builder sharedBuilder) {
		var requestFactory = new SimpleClientHttpRequestFactory();
		requestFactory.setConnectTimeout(CONNECT_TIMEOUT);
		requestFactory.setReadTimeout(READ_TIMEOUT);
		return sharedBuilder.clone().requestFactory(requestFactory).build();
	}

	private static BoundedHttpTransport boundedGoogleTransport() {
		return new BoundedHttpTransport(new NetHttpTransport(), CONNECT_TIMEOUT, READ_TIMEOUT);
	}

	@Override
	public PlayIntegrityVerdict decode(String integrityToken) {
		try {
			JsonNode response = restClient.post()
				.uri(DECODE_URL)
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + accessTokenProvider.accessToken())
				.body(Map.of("integrityToken", integrityToken))
				.retrieve()
				.body(JsonNode.class);
			return parse(response == null ? objectMapper.createObjectNode() : response);
		} catch (RestClientResponseException exception) {
			if (exception.getStatusCode().value() == 400) {
				return parse(objectMapper.createObjectNode());
			}
			throw new PlayIntegrityProviderUnavailableException(exception);
		} catch (RestClientException exception) {
			throw new PlayIntegrityProviderUnavailableException(exception);
		}
	}

	private PlayIntegrityVerdict parse(JsonNode response) {
		JsonNode payload = response.path("tokenPayloadExternal");
		JsonNode request = payload.path("requestDetails");
		JsonNode app = payload.path("appIntegrity");
		return new PlayIntegrityVerdict(
			text(request, "requestPackageName"),
			text(request, "requestHash"),
			timestamp(request.path("timestampMillis")),
			text(app, "packageName"),
			text(app, "appRecognitionVerdict"),
			texts(app.path("certificateSha256Digest")),
			text(payload.path("accountDetails"), "appLicensingVerdict"),
			texts(payload.path("deviceIntegrity").path("deviceRecognitionVerdict"))
		);
	}

	private String text(JsonNode parent, String field) {
		JsonNode value = parent.path(field);
		return value.isTextual() ? value.textValue() : null;
	}

	private List<String> texts(JsonNode values) {
		if (!values.isArray()) {
			return List.of();
		}
		return java.util.stream.StreamSupport.stream(values.spliterator(), false)
			.filter(JsonNode::isTextual)
			.map(JsonNode::textValue)
			.toList();
	}

	private Instant timestamp(JsonNode value) {
		try {
			return Instant.ofEpochMilli(Long.parseLong(value.asText()));
		} catch (NumberFormatException exception) {
			return null;
		}
	}

	@FunctionalInterface
	interface AccessTokenProvider {
		String accessToken();
	}

	private static final class GoogleAccessTokenProvider implements AccessTokenProvider {

		private final String credentialsBase64;
		private GoogleCredentials credentials;

		private GoogleAccessTokenProvider(String credentialsBase64) {
			this.credentialsBase64 = credentialsBase64;
		}

		@Override
		public synchronized String accessToken() {
			try {
				if (credentials == null) {
					credentials = loadCredentials().createScoped(PLAY_INTEGRITY_SCOPE);
				}
				credentials.refreshIfExpired();
				AccessToken token = credentials.getAccessToken();
				if (token == null) {
					token = credentials.refreshAccessToken();
				}
				return token.getTokenValue();
			} catch (IOException | RuntimeException exception) {
				throw new PlayIntegrityProviderUnavailableException(exception);
			}
		}

		private GoogleCredentials loadCredentials() throws IOException {
			if (credentialsBase64 == null || credentialsBase64.isBlank()) {
				return GoogleCredentials.getApplicationDefault(GooglePlayIntegrityDecoder::boundedGoogleTransport);
			}
			byte[] decoded = Base64.getDecoder().decode(credentialsBase64);
			return GoogleCredentials.fromStream(
				new ByteArrayInputStream(decoded),
				GooglePlayIntegrityDecoder::boundedGoogleTransport
			);
		}
	}

}
