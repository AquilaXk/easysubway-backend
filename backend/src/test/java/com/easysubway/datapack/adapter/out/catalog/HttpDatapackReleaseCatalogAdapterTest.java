package com.easysubway.datapack.adapter.out.catalog;

import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.Signature;
import java.util.Base64;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;

class HttpDatapackReleaseCatalogAdapterTest {
	private static final ObjectMapper JSON = new ObjectMapper();

	/**
	 * hub contracts/datapack/canonical-number-contract.json snapshot은 Node·Java·Dart 세 구현이
	 * 공유하는 정준 숫자 표기 계약이다. 기대 문자열은 세 런타임 실측으로 고정된 상수이며
	 * 이 테스트는 구현을 복제하지 않고 저장된 상수와만 비교한다.
	 */
	@Test
	void canonicalNumberFormattingMatchesSharedContract() throws Exception {
		for (JsonNode entry : canonicalNumberContract().get("formatting")) {
			var literal = entry.get("literal").asText();
			var parsed = HttpDatapackReleaseCatalogAdapter.JSON.readTree(literal);

			assertThat(HttpDatapackReleaseCatalogAdapter.ecmascriptNumber(parsed.decimalValue()))
				.as("formatting/%s (%s)", entry.get("id").asText(), literal)
				.isEqualTo(entry.get("canonical").asText());
		}
	}

	@Test
	void canonicalSerializationAcceptsOnlySafeRangeNumbersFromSharedContract() throws Exception {
		for (JsonNode entry : canonicalNumberContract().get("formatting")) {
			var literal = entry.get("literal").asText();
			var label = "formatting/" + entry.get("id").asText() + " (" + literal + ")";
			var document = HttpDatapackReleaseCatalogAdapter.JSON.readTree("{\"value\":" + literal + "}");

			if (entry.get("withinSafeRange").asBoolean()) {
				assertThat(new String(HttpDatapackReleaseCatalogAdapter.canonical(document), StandardCharsets.UTF_8))
					.as(label)
					.isEqualTo("{\"value\":" + entry.get("canonical").asText() + "}");
			} else {
				assertThatThrownBy(() -> HttpDatapackReleaseCatalogAdapter.canonical(document))
					.as(label)
					.isInstanceOf(IllegalArgumentException.class);
			}
		}
	}

	@Test
	void canonicalSerializationRejectsLiteralsWithoutAgreedCanonicalForm() throws Exception {
		for (JsonNode entry : canonicalNumberContract().get("rejectedLiterals")) {
			var literal = entry.get("literal").asText();
			var document = HttpDatapackReleaseCatalogAdapter.JSON.readTree("{\"value\":" + literal + "}");

			assertThatThrownBy(() -> HttpDatapackReleaseCatalogAdapter.canonical(document))
				.as("rejectedLiterals/%s (%s)", entry.get("id").asText(), literal)
				.isInstanceOf(IllegalArgumentException.class);
		}
	}

	@Test
	void canonicalSerializationRejectsNonFiniteNumbers() throws Exception {
		for (JsonNode entry : canonicalNumberContract().get("rejectedSpecialValues")) {
			var value = switch (entry.get("value").asText()) {
				case "Infinity" -> Double.POSITIVE_INFINITY;
				case "-Infinity" -> Double.NEGATIVE_INFINITY;
				case "NaN" -> Double.NaN;
				default -> throw new IllegalStateException("unknown special value");
			};
			var document = HttpDatapackReleaseCatalogAdapter.JSON.createObjectNode();
			document.put("value", value);

			assertThatThrownBy(() -> HttpDatapackReleaseCatalogAdapter.canonical(document))
				.as("rejectedSpecialValues/%s", entry.get("id").asText())
				.isInstanceOf(IllegalArgumentException.class);
		}
	}

	@Test
	void canonicalSerializationRejectsLiteralsThatAreNotShortestRoundTripDecimals() throws Exception {
		for (JsonNode entry : canonicalNumberContract().get("nonCanonicalLiterals")) {
			var literal = entry.get("literal").asText();
			var document = HttpDatapackReleaseCatalogAdapter.JSON.readTree("{\"value\":" + literal + "}");

			assertThatThrownBy(() -> HttpDatapackReleaseCatalogAdapter.canonical(document))
				.as("nonCanonicalLiterals/%s (%s)", entry.get("id").asText(), literal)
				.isInstanceOf(IllegalArgumentException.class);
		}
	}

	@Test
	void canonicalSerializationReproducesEveryRoundTripSample() throws Exception {
		var samples = canonicalNumberContract().get("roundTripSamples");
		assertThat(samples.size()).isGreaterThanOrEqualTo(300);
		for (JsonNode sample : samples) {
			var text = sample.asText();

			assertThat(HttpDatapackReleaseCatalogAdapter.ecmascriptNumber(
				HttpDatapackReleaseCatalogAdapter.JSON.readTree(text).decimalValue()))
				.as("roundTripSamples/%s", text)
				.isEqualTo(text);
		}
	}

	@Test
	void canonicalSerializationSortsKeysAndOmitsWhitespace() throws Exception {
		var value = JSON.readTree("""
			{"b":[1,true,null,"x"],"A":2,"a":{"z":3,"y":4}}
			""");

		assertThat(new String(HttpDatapackReleaseCatalogAdapter.canonical(value), StandardCharsets.UTF_8))
			.isEqualTo("{\"A\":2,\"a\":{\"y\":4,\"z\":3},\"b\":[1,true,null,\"x\"]}");
	}

	@Test
	void fetchCurrentFailsClosedWithoutConfiguredTrustMaterial() {
		var adapter = new HttpDatapackReleaseCatalogAdapter("", "", "production-v1");
		assertThatThrownBy(() -> adapter.fetchCurrent("production"))
			.isInstanceOf(com.easysubway.datapack.application.port.out.DatapackReleaseCatalogPort.Unavailable.class);
	}

	@Test
	void verifiesImmutableManifestSignatureAndIdentity() throws Exception {
		var keyPair = KeyPairGenerator.getInstance("RSA").generateKeyPair();
		var manifest = JSON.createObjectNode();
		manifest.put("manifestVersion", 2);
		manifest.put("channel", "production");
		manifest.put("releaseSequence", 42);
		manifest.put("keyId", "production-v1");
		manifest.put("ttlSeconds", 3600);
		manifest.putArray("packs");
		var signer = Signature.getInstance("SHA256withRSA");
		signer.initSign(keyPair.getPrivate());
		signer.update(HttpDatapackReleaseCatalogAdapter.canonical(manifest));
		var signature = manifest.putObject("signature");
		signature.put("algorithm", "rsa-sha256-manifest-v2");
		signature.put("value", Base64.getUrlEncoder().withoutPadding().encodeToString(signer.sign()));
		byte[] body = JSON.writeValueAsBytes(manifest);

		var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/catalog/releases/42.json", exchange -> {
			exchange.sendResponseHeaders(200, body.length);
			exchange.getResponseBody().write(body);
			exchange.close();
		});
		server.start();
		try {
			String publicKey = "-----BEGIN PUBLIC KEY-----\n"
				+ Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII))
					.encodeToString(keyPair.getPublic().getEncoded())
				+ "\n-----END PUBLIC KEY-----";
			var adapter = new HttpDatapackReleaseCatalogAdapter(
				"http://127.0.0.1:" + server.getAddress().getPort(),
				publicKey.replace("\n", "\\n"), "production-v1");

			var identity = adapter.fetch("production", 42);

			assertThat(identity.releaseSequence()).isEqualTo(42);
			assertThat(identity.channel()).isEqualTo("production");
			assertThat(identity.signatureValid()).isTrue();
			assertThat(identity.manifestSha256()).hasSize(64);
		} finally {
			server.stop(0);
		}
	}

	@Test
	void rejectsSignedManifestWithUnsupportedVersion() throws Exception {
		var keyPair = KeyPairGenerator.getInstance("RSA").generateKeyPair();
		byte[] body = signedManifest(keyPair, 42, 1, "");
		var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/catalog/current.json", exchange -> respond(exchange, body));
		server.start();
		try {
			var adapter = new HttpDatapackReleaseCatalogAdapter(
				"http://127.0.0.1:" + server.getAddress().getPort(), publicKey(keyPair), "production-v1");

			assertThatThrownBy(() -> adapter.fetchCurrent("production"))
				.isInstanceOf(
					com.easysubway.datapack.application.port.out.DatapackReleaseCatalogPort.Unavailable.class);
		} finally {
			server.stop(0);
		}
	}

	@Test
	void rejectsCatalogResponseLargerThanOneMebibyte() throws Exception {
		var keyPair = KeyPairGenerator.getInstance("RSA").generateKeyPair();
		byte[] body = signedManifest(keyPair, 42, 2, "x".repeat(1_048_576));
		var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/catalog/current.json", exchange -> respond(exchange, body));
		server.start();
		try {
			var adapter = new HttpDatapackReleaseCatalogAdapter(
				"http://127.0.0.1:" + server.getAddress().getPort(), publicKey(keyPair), "production-v1");

			assertThatThrownBy(() -> adapter.fetchCurrent("production"))
				.isInstanceOf(com.easysubway.datapack.application.port.out.DatapackReleaseCatalogPort.Unavailable.class);
		} finally {
			server.stop(0);
		}
	}

	@Test
	void returnsSignedIdentityForServiceMismatchClassification() throws Exception {
		var keyPair = KeyPairGenerator.getInstance("RSA").generateKeyPair();
		var manifest = JSON.createObjectNode();
		manifest.put("manifestVersion", 2);
		manifest.put("channel", "staging");
		manifest.put("releaseSequence", 41);
		manifest.put("keyId", "production-v1");
		manifest.put("ttlSeconds", 3600);
		manifest.putArray("packs");
		var signer = Signature.getInstance("SHA256withRSA");
		signer.initSign(keyPair.getPrivate());
		signer.update(HttpDatapackReleaseCatalogAdapter.canonical(manifest));
		var signature = manifest.putObject("signature");
		signature.put("algorithm", "rsa-sha256-manifest-v2");
		signature.put("value", Base64.getUrlEncoder().withoutPadding().encodeToString(signer.sign()));
		byte[] body = JSON.writeValueAsBytes(manifest);
		var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/catalog/releases/42.json", exchange -> {
			exchange.sendResponseHeaders(200, body.length);
			exchange.getResponseBody().write(body);
			exchange.close();
		});
		server.start();
		try {
			String publicKey = "-----BEGIN PUBLIC KEY-----\n"
				+ Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII))
					.encodeToString(keyPair.getPublic().getEncoded())
				+ "\n-----END PUBLIC KEY-----";
			var adapter = new HttpDatapackReleaseCatalogAdapter(
				"http://127.0.0.1:" + server.getAddress().getPort(), publicKey, "production-v1");
			var identity = adapter.fetch("production", 42);
			assertThat(identity.signatureValid()).isTrue();
			assertThat(identity.channel()).isEqualTo("staging");
			assertThat(identity.releaseSequence()).isEqualTo(41);
		} finally {
			server.stop(0);
		}
	}

	@Test
	void findsRequestThroughSignedBindingWithoutChangingManifestIdentity() throws Exception {
		var keyPair = KeyPairGenerator.getInstance("RSA").generateKeyPair();
		byte[] manifest = signedManifest(keyPair, 42);
		byte[] current = manifest;
		byte[] binding = signedBinding(
			keyPair, 42, "request-2057", sha256(manifest), "NO_CHANGE_VALID");
		var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/catalog/release-requests/"
			+ sha256("request-2057".getBytes(StandardCharsets.UTF_8)) + ".json",
			exchange -> respond(exchange, binding));
		server.createContext("/catalog/releases/42.json", exchange -> respond(exchange, manifest));
		server.createContext("/catalog/current.json", exchange -> respond(exchange, current));
		server.start();
		try {
			String publicKey = "-----BEGIN PUBLIC KEY-----\n"
				+ Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII))
					.encodeToString(keyPair.getPublic().getEncoded())
				+ "\n-----END PUBLIC KEY-----";
			var adapter = new HttpDatapackReleaseCatalogAdapter(
				"http://127.0.0.1:" + server.getAddress().getPort(), publicKey, "production-v1");

			var found = adapter.findByRequest("production", "request-2057");

			assertThat(found).get().extracting(identity -> identity.releaseSequence()).isEqualTo(42L);
			assertThat(found).get().extracting(identity -> identity.manifestSha256()).isEqualTo(sha256(manifest));
			assertThat(found).get().extracting(identity -> identity.noChange()).isEqualTo(true);
		} finally {
			server.stop(0);
		}
	}

	@Test
	void returnsImmutableRequestIdentityWhenALaterReleaseReplacedTheCurrentPointer() throws Exception {
		var keyPair = KeyPairGenerator.getInstance("RSA").generateKeyPair();
		byte[] manifest = signedManifest(keyPair, 42);
		byte[] current = signedManifest(keyPair, 44);
		byte[] binding = signedBinding(keyPair, 42, "request-2057", sha256(manifest));
		var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/catalog/release-requests/"
			+ sha256("request-2057".getBytes(StandardCharsets.UTF_8)) + ".json",
			exchange -> respond(exchange, binding));
		server.createContext("/catalog/releases/42.json", exchange -> respond(exchange, manifest));
		server.createContext("/catalog/current.json", exchange -> respond(exchange, current));
		server.start();
		try {
			String publicKey = "-----BEGIN PUBLIC KEY-----\n"
				+ Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII))
					.encodeToString(keyPair.getPublic().getEncoded())
				+ "\n-----END PUBLIC KEY-----";
			var adapter = new HttpDatapackReleaseCatalogAdapter(
				"http://127.0.0.1:" + server.getAddress().getPort(), publicKey, "production-v1");

			assertThat(adapter.findByRequest("production", "request-2057"))
				.get().extracting(identity -> identity.releaseSequence()).isEqualTo(42L);
		} finally {
			server.stop(0);
		}
	}

	private static byte[] signedManifest(java.security.KeyPair keyPair, long sequence)
		throws Exception {
		return signedManifest(keyPair, sequence, 2, "");
	}

	private static byte[] signedManifest(java.security.KeyPair keyPair, long sequence,
		int manifestVersion, String padding) throws Exception {
		var manifest = JSON.createObjectNode();
		manifest.put("manifestVersion", manifestVersion);
		manifest.put("channel", "production");
		manifest.put("releaseSequence", sequence);
		manifest.put("keyId", "production-v1");
		manifest.put("ttlSeconds", 3600);
		manifest.putArray("packs");
		if (!padding.isEmpty()) manifest.put("padding", padding);
		var signer = Signature.getInstance("SHA256withRSA");
		signer.initSign(keyPair.getPrivate());
		signer.update(HttpDatapackReleaseCatalogAdapter.canonical(manifest));
		var signature = manifest.putObject("signature");
		signature.put("algorithm", "rsa-sha256-manifest-v2");
		signature.put("value", Base64.getUrlEncoder().withoutPadding().encodeToString(signer.sign()));
		return JSON.writeValueAsBytes(manifest);
	}

	private static String publicKey(java.security.KeyPair keyPair) {
		return "-----BEGIN PUBLIC KEY-----\n"
			+ Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII))
				.encodeToString(keyPair.getPublic().getEncoded())
			+ "\n-----END PUBLIC KEY-----";
	}

	private static byte[] signedBinding(java.security.KeyPair keyPair, long sequence,
		String requestId, String manifestSha256) throws Exception {
		return signedBinding(keyPair, sequence, requestId, manifestSha256, "PUBLISHED_AND_VERIFIED");
	}

	private static byte[] signedBinding(java.security.KeyPair keyPair, long sequence,
		String requestId, String manifestSha256, String releaseOutcome) throws Exception {
		var binding = JSON.createObjectNode();
		binding.put("schemaVersion", 1);
		binding.put("artifactKind", "datapack-release-request-binding");
		binding.put("releaseRequestId", requestId);
		binding.put("releaseSequence", sequence);
		binding.put("channel", "production");
		binding.put("manifestSha256", manifestSha256);
		binding.put("keyId", "production-v1");
		binding.put("releaseOutcome", releaseOutcome);
		var signer = Signature.getInstance("SHA256withRSA");
		signer.initSign(keyPair.getPrivate());
		signer.update(HttpDatapackReleaseCatalogAdapter.canonical(binding));
		var signature = binding.putObject("signature");
		signature.put("algorithm", "rsa-sha256-release-request-v1");
		signature.put("value", Base64.getUrlEncoder().withoutPadding().encodeToString(signer.sign()));
		return JSON.writeValueAsBytes(binding);
	}

	private static JsonNode canonicalNumberContract() throws Exception {
		return JSON.readTree(requireNonNull(HttpDatapackReleaseCatalogAdapterTest.class
			.getResourceAsStream("/contracts/canonical-number-contract.json")));
	}

	private static String sha256(byte[] value) throws Exception {
		return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
	}

	private static void respond(com.sun.net.httpserver.HttpExchange exchange, byte[] body)
		throws java.io.IOException {
		exchange.sendResponseHeaders(200, body.length);
		exchange.getResponseBody().write(body);
		exchange.close();
	}
}
