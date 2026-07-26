package com.easysubway.datapack.adapter.out.catalog;

import com.easysubway.datapack.application.port.out.DatapackReleaseCatalogPort;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.cfg.JsonNodeFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
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
	// 정준 문자열은 리터럴의 정확한 십진 값에 결속된다. 계약 테스트가 같은 파서
	// 설정으로 fixture 리터럴을 읽어야 하므로 package-private으로 노출한다.
	static final ObjectMapper JSON = JsonMapper.builder()
		.enable(JsonNodeFeature.USE_BIG_DECIMAL_FOR_FLOATS)
		.disable(JsonNodeFeature.STRIP_TRAILING_BIGDECIMAL_ZEROES)
		.build();
	private static final Duration TIMEOUT = Duration.ofSeconds(10);
	private static final int MAX_CATALOG_BYTES = 1024 * 1024;
	private static final BigDecimal MAX_SAFE_CANONICAL_NUMBER = new BigDecimal("9007199254740991");
	private static final BigDecimal MIN_SAFE_CANONICAL_NUMBER = MAX_SAFE_CANONICAL_NUMBER.negate();

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
		if (value.isNumber()) return canonicalNumber(value);
		return value.toString();
	}

	/**
	 * 정준 숫자 표기 계약: contracts/datapack/canonical-number-contract.json
	 * Node·Dart는 JSON 숫자를 IEEE-754 배정도로 파싱하므로 정준 문자열이 언제나 그 배정도의
	 * 최단 왕복 십진 표기다. 이 어댑터는 USE_BIG_DECIMAL_FOR_FLOATS로 리터럴의 정확한 십진
	 * 값을 유지하므로, 리터럴이 자기 배정도의 최단 왕복 표기가 아니면 혼자 다른 정준 문자열을
	 * 만든다(1e-400·4e-324·비최단 소수). 세 축을 모두 fail closed로 닫는다.
	 * 유한성(배정도 오버플로·언더플로 아님) / 안전 정수 범위 / 최단 왕복 표기.
	 */
	private static String canonicalNumber(JsonNode value) {
		// USE_BIG_DECIMAL_FOR_FLOATS 때문에 파싱 경로는 DecimalNode를 만든다. 이 가드는
		// 코드로 조립한 DoubleNode/FloatNode(NaN·Infinity)를 막고, 파싱 경로의 오버플로는
		// 아래 decimalValue()의 배정도 변환이 잡는다.
		if ((value.isDouble() || value.isFloat()) && !Double.isFinite(value.doubleValue())) {
			throw new IllegalArgumentException("manifest canonical number must be finite");
		}
		var decimal = value.decimalValue();
		var binary = decimal.doubleValue();
		if (!Double.isFinite(binary)) {
			throw new IllegalArgumentException("manifest canonical number must be finite");
		}
		if (decimal.compareTo(MAX_SAFE_CANONICAL_NUMBER) > 0
			|| decimal.compareTo(MIN_SAFE_CANONICAL_NUMBER) < 0) {
			throw new IllegalArgumentException(
				"manifest canonical number must be within the safe integer range");
		}
		if (shortestRoundTripDecimal(binary).compareTo(decimal) != 0) {
			throw new IllegalArgumentException(
				"manifest canonical number must be the shortest round-trip decimal of its double");
		}
		return ecmascriptNumber(decimal);
	}

	/**
	 * ECMAScript Number::toString이 고르는 십진 표기 — 배정도로 왕복하는 것 중 유효 자릿수가
	 * 가장 적고, 같은 자릿수 안에서는 정확한 이진 값에 가장 가까운 값(동률은 짝수). Java
	 * Double.toString은 가수에 소수점 이하 한 자리를 강제해 최단형이 아니므로(MIN_VALUE →
	 * 4.9E-324, ECMAScript는 5e-324) 쓸 수 없다.
	 */
	private static BigDecimal shortestRoundTripDecimal(double value) {
		if (value == 0) return BigDecimal.ZERO;
		var exact = new BigDecimal(value);
		for (int precision = 1; precision <= 17; precision += 1) {
			var candidate = exact.round(new MathContext(precision, RoundingMode.HALF_EVEN));
			if (candidate.doubleValue() == value) return candidate;
		}
		return exact;
	}

	static String ecmascriptNumber(BigDecimal value) {
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
