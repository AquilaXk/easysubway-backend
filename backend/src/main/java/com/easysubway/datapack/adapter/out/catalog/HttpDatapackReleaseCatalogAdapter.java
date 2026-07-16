package com.easysubway.datapack.adapter.out.catalog;

import com.easysubway.datapack.application.port.out.DatapackReleaseCatalogPort;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.cfg.JsonNodeFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.time.Duration;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;
import java.util.stream.StreamSupport;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class HttpDatapackReleaseCatalogAdapter implements DatapackReleaseCatalogPort {
	private static final ObjectMapper JSON = JsonMapper.builder()
		.enable(JsonNodeFeature.USE_BIG_DECIMAL_FOR_FLOATS)
		.disable(JsonNodeFeature.STRIP_TRAILING_BIGDECIMAL_ZEROES)
		.build();
	private static final Duration TIMEOUT = Duration.ofSeconds(10);
	private static final int MAX_CATALOG_BYTES = 1024 * 1024;

	private final HttpClient httpClient;
	private final String baseUrl;
	private final String publicKeyPem;
	private final String keyId;

	@org.springframework.beans.factory.annotation.Autowired
	public HttpDatapackReleaseCatalogAdapter(
		@Value("${easysubway.datapack.catalog-base-url:}") String baseUrl,
		@Value("${easysubway.datapack.signing-public-key-pem:}") String publicKeyPem,
		@Value("${easysubway.datapack.signing-key-id:production-v1}") String keyId) {
		this(HttpClient.newBuilder().connectTimeout(TIMEOUT).build(), baseUrl, publicKeyPem, keyId);
	}

	HttpDatapackReleaseCatalogAdapter(HttpClient httpClient, String baseUrl, String publicKeyPem, String keyId) {
		this.httpClient = httpClient;
		this.baseUrl = baseUrl == null ? "" : baseUrl.replaceFirst("/+$", "");
		this.publicKeyPem = publicKeyPem == null ? "" : publicKeyPem.trim();
		this.keyId = keyId;
	}

	@Override
	public CatalogIdentity fetch(String channel, long releaseSequence) {
		return fetchPath("/catalog/releases/" + releaseSequence + ".json");
	}

	@Override
	public CatalogIdentity fetchCurrent(String channel) {
		return fetchPath("/catalog/current.json");
	}

	@Override
	public Optional<CatalogIdentity> findByRequest(String channel, String releaseRequestId) {
		try {
			byte[] bytes = fetchBytes("/catalog/release-requests/"
				+ sha256(releaseRequestId.getBytes(StandardCharsets.UTF_8)) + ".json");
			JsonNode binding = JSON.readTree(bytes);
			String signatureValue = binding.path("signature").path("value").asText("");
			boolean signatureValid = "rsa-sha256-release-request-v1".equals(
				binding.path("signature").path("algorithm").asText())
				&& binding.path("schemaVersion").asInt(-1) == 1
				&& "datapack-release-request-binding".equals(binding.path("artifactKind").asText())
				&& keyId.equals(binding.path("keyId").asText())
				&& verify(binding, signatureValue);
			long sequence = binding.path("releaseSequence").asLong(-1);
			String boundChannel = binding.path("channel").asText("");
				String boundRequestId = binding.path("releaseRequestId").asText("");
				String boundManifestSha256 = binding.path("manifestSha256").asText("");
				String releaseOutcome = binding.path("releaseOutcome").asText("");
				if (!signatureValid || sequence < 1 || !channel.equals(boundChannel)
					|| !releaseRequestId.equals(boundRequestId)
					|| !boundManifestSha256.matches("[a-f0-9]{64}")
					|| !("PUBLISHED_AND_VERIFIED".equals(releaseOutcome)
						|| "NO_CHANGE_VALID".equals(releaseOutcome))) throw new Unavailable();
			var manifest = fetch(channel, sequence);
			if (!manifest.signatureValid() || manifest.releaseSequence() != sequence
				|| !channel.equals(manifest.channel())
				|| !boundManifestSha256.equals(manifest.manifestSha256())) throw new Unavailable();
				return Optional.of(new CatalogIdentity(
					sequence, boundManifestSha256, channel, releaseRequestId, true,
					sha256(signatureValue.getBytes(StandardCharsets.UTF_8)),
					"NO_CHANGE_VALID".equals(releaseOutcome)));
		} catch (NotFound missing) {
			return Optional.empty();
		} catch (IOException | RuntimeException exception) {
			if (exception instanceof Unavailable unavailable) throw unavailable;
			throw new Unavailable();
		}
	}

	private CatalogIdentity fetchPath(String path) {
		try {
			byte[] bytes = fetchBytes(path);
			JsonNode manifest = JSON.readTree(bytes);
			String signatureValue = manifest.path("signature").path("value").asText("");
			boolean signatureValid = "rsa-sha256-manifest-v2".equals(
				manifest.path("signature").path("algorithm").asText())
				&& manifest.path("manifestVersion").asInt(-1) == 2
				&& keyId.equals(manifest.path("keyId").asText())
				&& verify(manifest, signatureValue);
			long actualSequence = manifest.path("releaseSequence").asLong(-1);
			String actualChannel = manifest.path("channel").asText("");
			return new CatalogIdentity(
				actualSequence, sha256(bytes), actualChannel,
				"", signatureValid,
				sha256(signatureValue.getBytes(StandardCharsets.UTF_8)));
		} catch (IOException | RuntimeException exception) {
			if (exception instanceof Unavailable unavailable) throw unavailable;
			throw new Unavailable();
		}
	}

	private byte[] fetchBytes(String path) {
		if (baseUrl.isBlank() || publicKeyPem.isBlank()) throw new Unavailable();
		try {
			var request = HttpRequest.newBuilder(URI.create(baseUrl + path))
				.timeout(TIMEOUT).GET().build();
			var response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
			try (var body = response.body()) {
				if (response.statusCode() == 404) throw new NotFound();
				if (response.statusCode() < 200 || response.statusCode() >= 300) throw new Unavailable();
				var bytes = body.readNBytes(MAX_CATALOG_BYTES + 1);
				if (bytes.length > MAX_CATALOG_BYTES) throw new Unavailable();
				return bytes;
			}
		} catch (InterruptedException interrupted) {
			Thread.currentThread().interrupt();
			throw new Unavailable();
		} catch (IOException | RuntimeException exception) {
			if (exception instanceof Unavailable unavailable) throw unavailable;
			throw new Unavailable();
		}
	}

	private boolean verify(JsonNode manifest, String signatureValue) {
		try {
			var verifier = Signature.getInstance("SHA256withRSA");
			verifier.initVerify(KeyFactory.getInstance("RSA").generatePublic(
				new X509EncodedKeySpec(pemBytes(publicKeyPem))));
			var unsigned = (ObjectNode) manifest.deepCopy();
			unsigned.remove("signature");
			verifier.update(canonical(unsigned));
			return verifier.verify(Base64.getUrlDecoder().decode(signatureValue));
		} catch (java.security.GeneralSecurityException | IllegalArgumentException exception) {
			return false;
		}
	}

	static byte[] canonical(JsonNode value) {
		return canonicalText(value).getBytes(StandardCharsets.UTF_8);
	}

	private static String canonicalText(JsonNode value) {
		if (value.isObject()) {
			return "{" + StreamSupport.stream(
				java.util.Spliterators.spliteratorUnknownSize(value.fieldNames(), 0), false)
				.sorted()
				.map(name -> quoted(name) + ":" + canonicalText(value.get(name)))
				.collect(java.util.stream.Collectors.joining(",")) + "}";
		}
		if (value.isArray()) {
			return "[" + StreamSupport.stream(value.spliterator(), false)
				.map(HttpDatapackReleaseCatalogAdapter::canonicalText)
				.collect(java.util.stream.Collectors.joining(",")) + "]";
		}
		if (value.isTextual()) return quoted(value.textValue());
		if (value.isNumber()) return ecmascriptNumber(value.decimalValue());
		return value.toString();
	}

	private static String ecmascriptNumber(BigDecimal value) {
		if (value.signum() == 0) return "0";
		var decimal = value.stripTrailingZeros();
		var absolute = decimal.abs();
		if (absolute.compareTo(new BigDecimal("0.000001")) >= 0
			&& absolute.compareTo(new BigDecimal("1e21")) < 0) return decimal.toPlainString();
		return decimal.toString().replace('E', 'e');
	}

	private static String quoted(String value) {
		try {
			return JSON.writeValueAsString(value);
		} catch (IOException impossible) {
			throw new IllegalStateException(impossible);
		}
	}

	private static byte[] pemBytes(String pem) {
		return Base64.getMimeDecoder().decode(pem.replace("\\n", "\n")
			.replace("-----BEGIN PUBLIC KEY-----", "")
			.replace("-----END PUBLIC KEY-----", ""));
	}

	private static String sha256(byte[] value) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
		} catch (java.security.GeneralSecurityException impossible) {
			throw new IllegalStateException(impossible);
		}
	}
}
