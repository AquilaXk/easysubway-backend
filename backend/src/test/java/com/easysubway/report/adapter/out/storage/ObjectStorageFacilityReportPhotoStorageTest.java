package com.easysubway.report.adapter.out.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.easysubway.report.application.port.out.StoreFacilityReportPhotoPort.StoreFacilityReportPhotoCommand;
import com.easysubway.report.application.port.out.StoreFacilityReportUploadedPhotoPort.StoreUploadedReportPhotoCommand;
import com.easysubway.report.domain.InvalidFacilityReportException;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class ObjectStorageFacilityReportPhotoStorageTest {

	private final Map<String, StoredObject> objects = new ConcurrentHashMap<>();
	private HttpServer server;

	@AfterEach
	void stopServer() {
		if (server != null) {
			server.stop(0);
		}
	}

	@Test
	void storesLoadsAndDeletesObjectsThroughSignedObjectStorageRequests() throws Exception {
		startObjectStorageServer();
		ObjectStorageFacilityReportPhotoStorage storage = new ObjectStorageFacilityReportPhotoStorage(
			"http://127.0.0.1:" + server.getAddress().getPort(),
			"easysubway-report-uploads",
			900L * 1024L,
			"prod-object-storage-access-key",
			"prod-object-storage-secret-key-with-enough-entropy",
			"ap-northeast-2",
			HttpClient.newHttpClient(),
			Clock.fixed(Instant.parse("2026-06-20T00:00:00Z"), ZoneOffset.UTC)
		);

		storage.storeUploadedReportPhoto(new StoreUploadedReportPhotoCommand(
			"facility-reports/unclaimed/object-1.jpg",
			new byte[] {1, 2, 3}
		));
		assertThat(storage.loadFacilityReportPhoto("facility-reports/unclaimed/object-1.jpg"))
			.hasValueSatisfying(photo -> {
				assertThat(photo.contentType()).isEqualTo("image/jpeg");
				assertThat(photo.bytes()).containsExactly(1, 2, 3);
			});

		var storedPhoto = storage.storeFacilityReportPhoto(new StoreFacilityReportPhotoCommand(
			"report-1",
			"photo.png",
			"image/png",
			new byte[] {4, 5, 6},
			new byte[] {7, 8},
			"0".repeat(64),
			3
		));

		assertThat(storedPhoto.objectKey()).isEqualTo("facility-reports/report-1/%s.png".formatted("0".repeat(64)));
		assertThat(storage.loadFacilityReportPhoto(storedPhoto.objectKey()))
			.hasValueSatisfying(photo -> {
				assertThat(photo.contentType()).isEqualTo("image/png");
				assertThat(photo.bytes()).containsExactly(4, 5, 6);
			});

		storage.deleteFacilityReportPhoto(storedPhoto.objectKey());

		assertThat(storage.loadFacilityReportPhoto(storedPhoto.objectKey())).isEmpty();
	}

	@Test
	void rejectsOversizedObjectBeforeReturningBufferedBytes() throws Exception {
		startObjectStorageServer();
		ObjectStorageFacilityReportPhotoStorage storage = new ObjectStorageFacilityReportPhotoStorage(
			"http://127.0.0.1:" + server.getAddress().getPort(),
			"easysubway-report-uploads",
			2L,
			"prod-object-storage-access-key",
			"prod-object-storage-secret-key-with-enough-entropy",
			"ap-northeast-2",
			HttpClient.newHttpClient(),
			Clock.fixed(Instant.parse("2026-06-20T00:00:00Z"), ZoneOffset.UTC)
		);
		objects.put(
			"/easysubway-report-uploads/facility-reports/unclaimed/oversized.jpg",
			new StoredObject("image/jpeg", new byte[] {1, 2, 3})
		);

		assertThatThrownBy(() -> storage.loadFacilityReportPhoto("facility-reports/unclaimed/oversized.jpg"))
			.isInstanceOf(InvalidFacilityReportException.class)
			.hasMessage("사진 파일 크기를 줄여야 합니다.");
	}

	@Test
	void rejectsObjectKeysOutsideFacilityReportPrefixBeforeSigningRequests() {
		ObjectStorageFacilityReportPhotoStorage storage = new ObjectStorageFacilityReportPhotoStorage(
			"http://127.0.0.1:1",
			"easysubway-report-uploads",
			900L * 1024L,
			"prod-object-storage-access-key",
			"prod-object-storage-secret-key-with-enough-entropy",
			"ap-northeast-2",
			HttpClient.newHttpClient(),
			Clock.fixed(Instant.parse("2026-06-20T00:00:00Z"), ZoneOffset.UTC)
		);

		for (String objectKey : new String[] {
			"other-prefix/object.jpg",
			"/facility-reports/unclaimed/object.jpg",
			"facility-reports/../object.jpg",
			"facility-reports//object.jpg",
			"facility-reports\\unclaimed\\object.jpg"
		}) {
			assertThat(storage.loadFacilityReportPhoto(objectKey)).isEmpty();
			storage.deleteFacilityReportPhoto(objectKey);
			assertThatThrownBy(() -> storage.storeUploadedReportPhoto(new StoreUploadedReportPhotoCommand(
				objectKey,
				new byte[] {1, 2, 3}
			)))
				.isInstanceOf(InvalidFacilityReportException.class)
				.hasMessage("사진 첨부 정보를 확인해야 합니다.");
		}
	}

	private void startObjectStorageServer() throws IOException {
		server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/", this::handleObjectRequest);
		server.start();
	}

	private void handleObjectRequest(HttpExchange exchange) throws IOException {
		assertThat(exchange.getRequestHeaders().getFirst("Authorization")).startsWith("AWS4-HMAC-SHA256 ");
		assertThat(exchange.getRequestHeaders().getFirst("x-amz-date")).isEqualTo("20260620T000000Z");
		String path = exchange.getRequestURI().getPath();
		switch (exchange.getRequestMethod()) {
			case "PUT" -> {
				byte[] body = exchange.getRequestBody().readAllBytes();
				objects.put(path, new StoredObject(
					exchange.getRequestHeaders().getFirst("Content-Type"),
					body
				));
				exchange.sendResponseHeaders(200, -1);
			}
			case "GET" -> {
				StoredObject object = objects.get(path);
				if (object == null) {
					exchange.sendResponseHeaders(404, -1);
					return;
				}
				exchange.getResponseHeaders().set("Content-Type", object.contentType());
				exchange.sendResponseHeaders(200, object.bytes().length);
				exchange.getResponseBody().write(object.bytes());
			}
			case "DELETE" -> {
				objects.remove(path);
				exchange.sendResponseHeaders(204, -1);
			}
			default -> exchange.sendResponseHeaders(405, -1);
		}
		exchange.close();
	}

	private record StoredObject(String contentType, byte[] bytes) {
	}
}
