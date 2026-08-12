package com.easysubway.journey.bundle;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.Authenticator;
import java.net.CookieHandler;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSession;
import org.junit.jupiter.api.Test;

class RouteBundlePublicationObjectFetcherTest {

	private static final String BASE_URL =
		"https://objectstorage.ap-seoul-1.oraclecloud.com/n/testnamespace/b/easysubway-route-bundles/o";
	private static final String PREFIX = "server-route-bundles/v1/" + "a".repeat(64) + "/";
	private static final List<String> PATHS = List.of(
		"compatibility.json",
		"manifest.json",
		"manifest.signing-input.json",
		"payload/accessibility.sqlite.zst",
		"payload/fare.sqlite.zst",
		"payload/timetable.sqlite.zst",
		"payload/topology.sqlite.zst",
		"provenance.json");

	@Test
	void fetchesExactEightObjectsOnceInDescriptorOrderAndDefensivelyCopiesBytes() {
		List<byte[]> bodies = PATHS.stream()
			.map(path -> ("bytes:" + path).getBytes(java.nio.charset.StandardCharsets.UTF_8))
			.toList();
		var verified = verifiedDescriptor(bodies.stream().mapToLong(bytes -> bytes.length).boxed().toList());
		var client = new StubHttpClient(request -> {
			int index = clientRequestIndex(request);
			return response(request, 200, request.uri(), Map.of(
				"Content-Length", List.of(String.valueOf(bodies.get(index).length))), bodies.get(index));
		});
		var fetcher = new RouteBundlePublicationObjectFetcher(
			client, Duration.ofSeconds(30), 64L * 1024 * 1024, BASE_URL);

		var fetched = fetcher.fetch(verified);

		assertEquals("d".repeat(64), fetched.descriptorSha256());
		assertEquals("launch-2026", fetched.keyId());
		assertEquals(PATHS, fetched.objects().stream().map(
			RouteBundlePublicationObjectFetcher.FetchedObject::path).toList());
		assertEquals(PATHS.size(), client.requests().size());
		for (int index = 0; index < PATHS.size(); index++) {
			HttpRequest request = client.requests().get(index);
			assertEquals("GET", request.method());
			assertEquals(URI.create(BASE_URL + "/" + PREFIX + PATHS.get(index)), request.uri());
			assertEquals(Optional.of(Duration.ofSeconds(30)), request.timeout());
			assertArrayEquals(bodies.get(index), fetched.objects().get(index).bytes());
		}

		byte[] firstRead = fetched.objects().getFirst().bytes();
		firstRead[0] ^= 1;
		assertArrayEquals(bodies.getFirst(), fetched.objects().getFirst().bytes());
	}

	@Test
	void fetchesFromRawCanonicalDescriptorBeforeTheSigningInputExistsLocally() throws Exception {
		var fixture = RouteBundleObjectAdmissionTest.fixture();
		var client = new StubHttpClient(request -> {
			int index = clientRequestIndex(request);
			byte[] body = fixture.objects().get(PATHS.get(index));
			return response(request, 200, request.uri(), Map.of(
				"Content-Length", List.of(String.valueOf(body.length))), body);
		});
		var fetcher = new RouteBundlePublicationObjectFetcher(
			client, Duration.ofSeconds(30), 64L * 1024 * 1024, BASE_URL);

		var fetched = fetcher.fetch(fixture.descriptorBytes(), RouteBundleObjectAdmissionTest.ACTIVATION_REQUEST);

		assertEquals(fixture.descriptorSha256(), fetched.descriptorSha256());
		assertEquals("launch-2026", fetched.keyId());
		assertEquals(PATHS, fetched.objects().stream()
			.map(RouteBundlePublicationObjectFetcher.FetchedObject::path).toList());
		assertEquals(PATHS.size(), client.requests().size());
	}

	@Test
	void rejectsMissingRawDescriptorBeforeAnyNetworkRequest() {
		var client = new StubHttpClient(request -> {
			throw new AssertionError("network must not be called");
		});
		var fetcher = new RouteBundlePublicationObjectFetcher(client, Duration.ofSeconds(30), 64L * 1024 * 1024);

		assertThrows(RouteBundleHandoffException.class,
			() -> fetcher.fetch((byte[]) null, RouteBundleObjectAdmissionTest.ACTIVATION_REQUEST));
		assertTrue(client.requests().isEmpty());
	}

	@Test
	void rejectsUntrustedRawDescriptorOriginBeforeAnyNetworkRequest() throws Exception {
		var exactFixture = RouteBundleObjectAdmissionTest.fixture();
		var otherOriginFixture = RouteBundleObjectAdmissionTest.fixture(
			"https://objectstorage.ap-seoul-1.oraclecloud.com/n/othernamespace/b/easysubway-route-bundles/o");
		var client = new StubHttpClient(request -> {
			throw new AssertionError("network must not be called");
		});
		for (var invocation : List.<org.junit.jupiter.api.function.Executable>of(
			() -> new RouteBundlePublicationObjectFetcher(
				client, Duration.ofSeconds(30), 64L * 1024 * 1024)
				.fetch(exactFixture.descriptorBytes(), RouteBundleObjectAdmissionTest.ACTIVATION_REQUEST),
			() -> new RouteBundlePublicationObjectFetcher(
				client, Duration.ofSeconds(30), 64L * 1024 * 1024, BASE_URL)
				.fetch(otherOriginFixture.descriptorBytes(), RouteBundleObjectAdmissionTest.ACTIVATION_REQUEST))) {
			var failure = assertThrows(
				RouteBundlePublicationObjectFetcher.AcquisitionException.class, invocation);
			assertEquals(RouteBundlePublicationObjectFetcher.Reason.UNTRUSTED_DESCRIPTOR_ORIGIN, failure.reason());
		}
		assertTrue(client.requests().isEmpty());
	}

	@Test
	void rejectsAggregateSizeBeforeAnyNetworkRequest() {
		var verified = verifiedDescriptor(declaredSizes(64L * 1024 * 1024 + 1));
		var client = new StubHttpClient(request -> {
			throw new AssertionError("network must not be called");
		});
		var fetcher = new RouteBundlePublicationObjectFetcher(client, Duration.ofSeconds(30), 64L * 1024 * 1024);

		var failure = assertThrows(
			RouteBundlePublicationObjectFetcher.AcquisitionException.class,
			() -> fetcher.fetch(verified));

		assertEquals(RouteBundlePublicationObjectFetcher.Reason.SIZE_LIMIT_EXCEEDED, failure.reason());
		assertTrue(client.requests().isEmpty());
	}

	@Test
	void rejectsResponseBoundaryMismatchAtFirstObjectWithoutContinuing() {
		byte[] body = new byte[] {1, 2, 3};
		List<ResponseCase> cases = List.of(
			new ResponseCase(503, URI.create(BASE_URL + "/" + PREFIX + PATHS.getFirst()), Map.of(), body,
				RouteBundlePublicationObjectFetcher.Reason.HTTP_STATUS_INVALID),
			new ResponseCase(200, URI.create(BASE_URL + "/redirected"), Map.of(), body,
				RouteBundlePublicationObjectFetcher.Reason.RESPONSE_URI_MISMATCH),
			new ResponseCase(200, URI.create(BASE_URL + "/" + PREFIX + PATHS.getFirst()),
				Map.of("Content-Encoding", List.of("gzip")), body,
				RouteBundlePublicationObjectFetcher.Reason.CONTENT_ENCODING_INVALID),
			new ResponseCase(200, URI.create(BASE_URL + "/" + PREFIX + PATHS.getFirst()),
				Map.of("Content-Length", List.of("4")), body,
				RouteBundlePublicationObjectFetcher.Reason.CONTENT_LENGTH_MISMATCH),
			new ResponseCase(200, URI.create(BASE_URL + "/" + PREFIX + PATHS.getFirst()), Map.of(),
				new byte[] {1, 2}, RouteBundlePublicationObjectFetcher.Reason.BODY_SIZE_MISMATCH),
			new ResponseCase(200, URI.create(BASE_URL + "/" + PREFIX + PATHS.getFirst()), Map.of(),
				new byte[] {1, 2, 3, 4}, RouteBundlePublicationObjectFetcher.Reason.BODY_SIZE_MISMATCH));

		for (ResponseCase candidate : cases) {
			var verified = verifiedDescriptor(declaredSizes(3));
			var client = new StubHttpClient(request -> response(
				request, candidate.status(), candidate.uri(), candidate.headers(), candidate.body()));
			var fetcher = new RouteBundlePublicationObjectFetcher(
				client, Duration.ofSeconds(30), 64L * 1024 * 1024);

			var failure = assertThrows(
				RouteBundlePublicationObjectFetcher.AcquisitionException.class,
				() -> fetcher.fetch(verified));
			assertEquals(candidate.reason(), failure.reason());
			assertEquals(1, client.requests().size());
		}
	}

	@Test
	void transportAndInterruptionFailuresUseOneAttemptWithoutFallback() {
		var verified = verifiedDescriptor(declaredSizes(3));
		var ioClient = new StubHttpClient(request -> {
			throw new IOException("network down");
		});
		var fetcher = new RouteBundlePublicationObjectFetcher(
			ioClient, Duration.ofSeconds(30), 64L * 1024 * 1024);

		var ioFailure = assertThrows(
			RouteBundlePublicationObjectFetcher.AcquisitionException.class,
			() -> fetcher.fetch(verified));
		assertEquals(RouteBundlePublicationObjectFetcher.Reason.TRANSPORT_FAILURE, ioFailure.reason());
		assertEquals(1, ioClient.requests().size());

		var interruptedClient = new StubHttpClient(request -> {
			throw new InterruptedException("cancelled");
		});
		var interruptedFetcher = new RouteBundlePublicationObjectFetcher(
			interruptedClient, Duration.ofSeconds(30), 64L * 1024 * 1024);
		try {
			var interruptedFailure = assertThrows(
				RouteBundlePublicationObjectFetcher.AcquisitionException.class,
				() -> interruptedFetcher.fetch(verified));
			assertEquals(RouteBundlePublicationObjectFetcher.Reason.TRANSPORT_FAILURE, interruptedFailure.reason());
			assertTrue(Thread.currentThread().isInterrupted());
			assertEquals(1, interruptedClient.requests().size());
		} finally {
			assertTrue(Thread.interrupted());
			assertFalse(Thread.currentThread().isInterrupted());
		}
	}

	@Test
	void boundsTheCompleteBodyReadAndClosesAStalledResponse() {
		var verified = verifiedDescriptor(declaredSizes(3));
		var stalledBody = new StalledInputStream();
		var client = new StubHttpClient(request -> response(
			request, 200, request.uri(), Map.of(), stalledBody));
		var fetcher = new RouteBundlePublicationObjectFetcher(
			client, Duration.ofMillis(50), 64L * 1024 * 1024);
		long startedAt = System.nanoTime();

		var failure = assertThrows(
			RouteBundlePublicationObjectFetcher.AcquisitionException.class,
			() -> fetcher.fetch(verified));

		assertEquals(RouteBundlePublicationObjectFetcher.Reason.TRANSPORT_FAILURE, failure.reason());
		assertTrue(Duration.ofNanos(System.nanoTime() - startedAt).compareTo(Duration.ofMillis(500)) < 0);
		assertTrue(stalledBody.closed());
		assertEquals(1, client.requests().size());
	}

	private static RouteBundleCurrentKeyVerifier.VerifiedPublicationDescriptorSignature verifiedDescriptor(
		List<Long> sizes) {
		var descriptor = mock(RouteBundlePublicationDescriptor.class);
		when(descriptor.descriptorSha256()).thenReturn("d".repeat(64));
		when(descriptor.locator()).thenReturn(
			new RouteBundlePublicationDescriptor.PublicationLocator(BASE_URL, PREFIX));
		var objects = new ArrayList<RouteBundlePublicationDescriptor.PublishedObject>();
		for (int index = 0; index < sizes.size(); index++) {
			String path = PATHS.get(index);
			objects.add(new RouteBundlePublicationDescriptor.PublishedObject(
				path, PREFIX + path, sizes.get(index), Integer.toHexString(index).repeat(64)));
		}
		when(descriptor.objects()).thenReturn(List.copyOf(objects));
		var verified = mock(RouteBundleCurrentKeyVerifier.VerifiedPublicationDescriptorSignature.class);
		when(verified.descriptor()).thenReturn(descriptor);
		when(verified.keyId()).thenReturn("launch-2026");
		return verified;
	}

	private static List<Long> declaredSizes(long first) {
		var sizes = new ArrayList<Long>(PATHS.size());
		sizes.add(first);
		while (sizes.size() < PATHS.size()) sizes.add(3L);
		return List.copyOf(sizes);
	}

	private static int clientRequestIndex(HttpRequest request) {
		String uri = request.uri().toString();
		for (int index = 0; index < PATHS.size(); index++) {
			if (uri.endsWith(PATHS.get(index))) return index;
		}
		throw new AssertionError("unexpected request URI: " + uri);
	}

	private static HttpResponse<InputStream> response(
		HttpRequest request,
		int status,
		URI uri,
		Map<String, List<String>> headers,
		byte[] body) {
		return response(request, status, uri, headers, new ByteArrayInputStream(body));
	}

	private static HttpResponse<InputStream> response(
		HttpRequest request,
		int status,
		URI uri,
		Map<String, List<String>> headers,
		InputStream body) {
		return new HttpResponse<>() {
			@Override public int statusCode() { return status; }
			@Override public HttpRequest request() { return request; }
			@Override public Optional<HttpResponse<InputStream>> previousResponse() { return Optional.empty(); }
			@Override public HttpHeaders headers() { return HttpHeaders.of(headers, (name, value) -> true); }
			@Override public InputStream body() { return body; }
			@Override public Optional<SSLSession> sslSession() { return Optional.empty(); }
			@Override public URI uri() { return uri; }
			@Override public HttpClient.Version version() { return HttpClient.Version.HTTP_2; }
		};
	}

	private static final class StalledInputStream extends InputStream {
		private final AtomicBoolean closed = new AtomicBoolean();

		@Override
		public int read() throws IOException {
			byte[] single = new byte[1];
			return read(single, 0, 1) < 0 ? -1 : Byte.toUnsignedInt(single[0]);
		}

		@Override
		public int read(byte[] bytes, int offset, int length) throws IOException {
			try {
				Thread.sleep(750);
				return -1;
			} catch (InterruptedException exception) {
				Thread.currentThread().interrupt();
				throw new IOException("stalled body interrupted", exception);
			}
		}

		@Override
		public void close() {
			closed.set(true);
		}

		private boolean closed() {
			return closed.get();
		}
	}

	@FunctionalInterface
	private interface Sender {
		HttpResponse<InputStream> send(HttpRequest request) throws IOException, InterruptedException;
	}

	private static final class StubHttpClient extends HttpClient {
		private final Sender sender;
		private final List<HttpRequest> requests = new CopyOnWriteArrayList<>();

		private StubHttpClient(Sender sender) {
			this.sender = sender;
		}

		private List<HttpRequest> requests() {
			return List.copyOf(requests);
		}

		@Override public Optional<CookieHandler> cookieHandler() { return Optional.empty(); }
		@Override public Optional<Duration> connectTimeout() { return Optional.of(Duration.ofSeconds(10)); }
		@Override public Redirect followRedirects() { return Redirect.NEVER; }
		@Override public Optional<ProxySelector> proxy() { return Optional.empty(); }
		@Override public SSLContext sslContext() {
			try {
				return SSLContext.getDefault();
			} catch (NoSuchAlgorithmException exception) {
				throw new IllegalStateException(exception);
			}
		}
		@Override public SSLParameters sslParameters() { return new SSLParameters(); }
		@Override public Optional<Authenticator> authenticator() { return Optional.empty(); }
		@Override public Version version() { return Version.HTTP_2; }
		@Override public Optional<Executor> executor() { return Optional.empty(); }

		@Override
		@SuppressWarnings("unchecked")
		public <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> responseBodyHandler)
			throws IOException, InterruptedException {
			requests.add(request);
			return (HttpResponse<T>) sender.send(request);
		}

		@Override
		public <T> CompletableFuture<HttpResponse<T>> sendAsync(
			HttpRequest request, HttpResponse.BodyHandler<T> responseBodyHandler) {
			throw new UnsupportedOperationException();
		}

		@Override
		public <T> CompletableFuture<HttpResponse<T>> sendAsync(
			HttpRequest request,
			HttpResponse.BodyHandler<T> responseBodyHandler,
			HttpResponse.PushPromiseHandler<T> pushPromiseHandler) {
			throw new UnsupportedOperationException();
		}
	}

	private record ResponseCase(
		int status,
		URI uri,
		Map<String, List<String>> headers,
		byte[] body,
		RouteBundlePublicationObjectFetcher.Reason reason) {
	}
}
