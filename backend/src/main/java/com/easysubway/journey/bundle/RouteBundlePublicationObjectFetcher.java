package com.easysubway.journey.bundle;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

/** Downloads one current-key-verified descriptor's immutable objects without admitting them. */
public final class RouteBundlePublicationObjectFetcher {

	private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
	private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);
	private static final long MAX_TOTAL_BYTES = 64L * 1024 * 1024;
	private static final List<String> OBJECT_PATHS = List.of(
		"compatibility.json",
		"manifest.json",
		"manifest.signing-input.json",
		"payload/accessibility.sqlite.zst",
		"payload/fare.sqlite.zst",
		"payload/timetable.sqlite.zst",
		"payload/topology.sqlite.zst",
		"provenance.json");

	private final HttpClient httpClient;
	private final Duration requestTimeout;
	private final long maxTotalBytes;

	public RouteBundlePublicationObjectFetcher() {
		this(
			HttpClient.newBuilder()
				.connectTimeout(CONNECT_TIMEOUT)
				.followRedirects(HttpClient.Redirect.NEVER)
				.build(),
			REQUEST_TIMEOUT,
			MAX_TOTAL_BYTES);
	}

	RouteBundlePublicationObjectFetcher(
		HttpClient httpClient,
		Duration requestTimeout,
		long maxTotalBytes) {
		this.httpClient = Objects.requireNonNull(httpClient, "httpClient");
		this.requestTimeout = Objects.requireNonNull(requestTimeout, "requestTimeout");
		if (requestTimeout.isZero() || requestTimeout.isNegative()) {
			throw new IllegalArgumentException("requestTimeout must be positive");
		}
		if (maxTotalBytes < 1) {
			throw new IllegalArgumentException("maxTotalBytes must be positive");
		}
		this.maxTotalBytes = maxTotalBytes;
	}

	public FetchedPublicationObjects fetch(
		RouteBundleCurrentKeyVerifier.VerifiedPublicationDescriptorSignature verified) {
		if (verified == null || verified.descriptor() == null) {
			throw failure(Reason.VERIFIED_DESCRIPTOR_INVALID, "verified publication descriptor is required");
		}
		RouteBundlePublicationDescriptor descriptor = verified.descriptor();
		PreparedDescriptor prepared = prepare(descriptor);
		var fetched = new ArrayList<FetchedObject>(prepared.objects().size());
		for (PreparedObject object : prepared.objects()) {
			fetched.add(fetchObject(object));
		}
		return new FetchedPublicationObjects(
			descriptor.descriptorSha256(), verified.keyId(), fetched);
	}

	private PreparedDescriptor prepare(RouteBundlePublicationDescriptor descriptor) {
		RouteBundlePublicationDescriptor.PublicationLocator locator = descriptor.locator();
		List<RouteBundlePublicationDescriptor.PublishedObject> objects = descriptor.objects();
		if (locator == null || objects == null || objects.size() != OBJECT_PATHS.size()) {
			throw failure(Reason.VERIFIED_DESCRIPTOR_INVALID, "verified descriptor object inventory is invalid");
		}
		URI baseUri = baseUri(locator.publicBaseUrl());
		var prepared = new ArrayList<PreparedObject>(objects.size());
		long totalBytes = 0;
		for (int index = 0; index < objects.size(); index++) {
			RouteBundlePublicationDescriptor.PublishedObject object = objects.get(index);
			String expectedPath = OBJECT_PATHS.get(index);
			if (object == null
				|| !expectedPath.equals(object.path())
				|| !object.objectKey().equals(locator.objectPrefix() + object.path())
				|| object.sizeBytes() < 1) {
				throw failure(Reason.VERIFIED_DESCRIPTOR_INVALID, "verified descriptor object identity is invalid");
			}
			try {
				totalBytes = Math.addExact(totalBytes, object.sizeBytes());
			} catch (ArithmeticException exception) {
				throw failure(Reason.SIZE_LIMIT_EXCEEDED, "publication object size total overflow", exception);
			}
			if (totalBytes > maxTotalBytes) {
				throw failure(Reason.SIZE_LIMIT_EXCEEDED, "publication objects exceed the acquisition limit");
			}
			prepared.add(new PreparedObject(
				object.path(),
				object.objectKey(),
				object.sizeBytes(),
				objectUri(baseUri, object.objectKey())));
		}
		return new PreparedDescriptor(List.copyOf(prepared));
	}

	private static URI baseUri(String value) {
		try {
			URI uri = URI.create(value);
			if (!"https".equals(uri.getScheme())
				|| uri.getUserInfo() != null
				|| uri.getQuery() != null
				|| uri.getFragment() != null
				|| uri.getHost() == null
				|| uri.getPath().endsWith("/")) {
				throw new IllegalArgumentException("invalid locator");
			}
			return uri;
		} catch (RuntimeException exception) {
			throw failure(Reason.VERIFIED_DESCRIPTOR_INVALID, "verified descriptor locator is invalid", exception);
		}
	}

	private static URI objectUri(URI baseUri, String objectKey) {
		try {
			URI uri = URI.create(baseUri + "/" + objectKey);
			if (!baseUri.getScheme().equals(uri.getScheme())
				|| !baseUri.getHost().equals(uri.getHost())
				|| baseUri.getPort() != uri.getPort()
				|| uri.getQuery() != null
				|| uri.getFragment() != null) {
				throw new IllegalArgumentException("invalid object URI");
			}
			return uri;
		} catch (RuntimeException exception) {
			throw failure(Reason.VERIFIED_DESCRIPTOR_INVALID, "verified descriptor object URI is invalid", exception);
		}
	}

	private FetchedObject fetchObject(PreparedObject object) {
		HttpRequest request = HttpRequest.newBuilder(object.uri())
			.timeout(requestTimeout)
			.GET()
			.build();
		var activeBody = new AtomicReference<InputStream>();
		var task = new FutureTask<>(() -> fetchObject(object, request, activeBody));
		Thread.ofVirtual().name("route-bundle-publication-object-fetch").start(task);
		try {
			return task.get(requestTimeout.toNanos(), TimeUnit.NANOSECONDS);
		} catch (TimeoutException exception) {
			cancel(task, activeBody);
			throw failure(Reason.TRANSPORT_FAILURE, "publication object request timed out", exception);
		} catch (InterruptedException exception) {
			cancel(task, activeBody);
			Thread.currentThread().interrupt();
			throw failure(Reason.TRANSPORT_FAILURE, "publication object request was interrupted", exception);
		} catch (ExecutionException exception) {
			Throwable cause = exception.getCause();
			if (cause instanceof AcquisitionException acquisitionException) {
				if (acquisitionException.getCause() instanceof InterruptedException) {
					Thread.currentThread().interrupt();
				}
				throw acquisitionException;
			}
			throw failure(Reason.TRANSPORT_FAILURE, "publication object request failed", cause);
		}
	}

	private FetchedObject fetchObject(
		PreparedObject object,
		HttpRequest request,
		AtomicReference<InputStream> activeBody) {
		HttpResponse<InputStream> response;
		try {
			response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
		} catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			throw failure(Reason.TRANSPORT_FAILURE, "publication object request was interrupted", exception);
		} catch (IOException | RuntimeException exception) {
			throw failure(Reason.TRANSPORT_FAILURE, "publication object request failed", exception);
		}
		if (response == null) {
			throw failure(Reason.TRANSPORT_FAILURE, "publication object response is missing");
		}
		InputStream body = response.body();
		activeBody.set(body);
		try {
			if (Thread.currentThread().isInterrupted()) {
				throw failure(Reason.TRANSPORT_FAILURE, "publication object request was interrupted");
			}
			if (response.statusCode() != 200) {
				throw failure(Reason.HTTP_STATUS_INVALID, "publication object response must be HTTP 200");
			}
			if (!object.uri().equals(response.uri())) {
				throw failure(Reason.RESPONSE_URI_MISMATCH, "publication object response URI changed");
			}
			if (!response.headers().allValues("Content-Encoding").isEmpty()) {
				throw failure(Reason.CONTENT_ENCODING_INVALID, "publication object response must be raw bytes");
			}
			validateContentLength(response, object.sizeBytes());
			byte[] bytes = readExact(body, object.sizeBytes());
			return new FetchedObject(object.path(), object.objectKey(), bytes);
		} finally {
			if (activeBody.compareAndSet(body, null)) {
				closeQuietly(body);
			}
		}
	}

	private static void cancel(FutureTask<?> task, AtomicReference<InputStream> activeBody) {
		task.cancel(true);
		closeQuietly(activeBody.getAndSet(null));
	}

	private static void validateContentLength(HttpResponse<InputStream> response, long expected) {
		List<String> values = response.headers().allValues("Content-Length");
		if (values.isEmpty()) return;
		if (values.size() != 1) {
			closeQuietly(response.body());
			throw failure(Reason.CONTENT_LENGTH_MISMATCH, "publication object content length is ambiguous");
		}
		try {
			if (Long.parseLong(values.getFirst()) != expected) {
				closeQuietly(response.body());
				throw failure(Reason.CONTENT_LENGTH_MISMATCH, "publication object content length mismatch");
			}
		} catch (NumberFormatException exception) {
			closeQuietly(response.body());
			throw failure(Reason.CONTENT_LENGTH_MISMATCH, "publication object content length is invalid", exception);
		}
	}

	private static byte[] readExact(InputStream source, long expected) {
		if (source == null) {
			throw failure(Reason.BODY_SIZE_MISMATCH, "publication object body is missing");
		}
		try (source; var output = new ByteArrayOutputStream((int) Math.min(expected, 8192))) {
			byte[] buffer = new byte[64 * 1024];
			long total = 0;
			for (int read; (read = source.read(buffer)) >= 0;) {
				if (read == 0) continue;
				total = Math.addExact(total, read);
				if (total > expected) {
					throw failure(Reason.BODY_SIZE_MISMATCH, "publication object body exceeds declared size");
				}
				output.write(buffer, 0, read);
			}
			if (total != expected) {
				throw failure(Reason.BODY_SIZE_MISMATCH, "publication object body size mismatch");
			}
			return output.toByteArray();
		} catch (AcquisitionException exception) {
			throw exception;
		} catch (IOException | ArithmeticException exception) {
			throw failure(Reason.TRANSPORT_FAILURE, "publication object body read failed", exception);
		}
	}

	private static void closeQuietly(InputStream source) {
		if (source == null) return;
		try {
			source.close();
		} catch (IOException ignored) {
			// A failed response is never published, regardless of cleanup outcome.
		}
	}

	private static AcquisitionException failure(Reason reason, String message) {
		return new AcquisitionException(reason, message, null);
	}

	private static AcquisitionException failure(Reason reason, String message, Throwable cause) {
		return new AcquisitionException(reason, message, cause);
	}

	public enum Reason {
		VERIFIED_DESCRIPTOR_INVALID,
		SIZE_LIMIT_EXCEEDED,
		TRANSPORT_FAILURE,
		HTTP_STATUS_INVALID,
		RESPONSE_URI_MISMATCH,
		CONTENT_ENCODING_INVALID,
		CONTENT_LENGTH_MISMATCH,
		BODY_SIZE_MISMATCH
	}

	public static final class AcquisitionException extends RuntimeException {
		private final Reason reason;

		private AcquisitionException(Reason reason, String message, Throwable cause) {
			super(message, cause);
			this.reason = Objects.requireNonNull(reason, "reason");
		}

		public Reason reason() {
			return reason;
		}
	}

	public record FetchedPublicationObjects(
		String descriptorSha256,
		String keyId,
		List<FetchedObject> objects) {
		public FetchedPublicationObjects {
			descriptorSha256 = Objects.requireNonNull(descriptorSha256, "descriptorSha256");
			keyId = Objects.requireNonNull(keyId, "keyId");
			objects = List.copyOf(objects);
		}
	}

	public record FetchedObject(String path, String objectKey, byte[] bytes) {
		public FetchedObject {
			path = Objects.requireNonNull(path, "path");
			objectKey = Objects.requireNonNull(objectKey, "objectKey");
			bytes = bytes.clone();
		}

		@Override
		public byte[] bytes() {
			return bytes.clone();
		}
	}

	private record PreparedDescriptor(List<PreparedObject> objects) {
	}

	private record PreparedObject(String path, String objectKey, long sizeBytes, URI uri) {
	}
}
