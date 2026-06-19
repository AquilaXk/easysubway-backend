package com.easysubway.report.adapter.out.storage;

import static org.assertj.core.api.Assertions.assertThat;

import com.easysubway.report.application.port.out.StoreFacilityReportPhotoPort.StoreFacilityReportPhotoCommand;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LocalFacilityReportPhotoStorageTest {

	@TempDir
	Path tempDir;

	@Test
	void storesLoadsAndDeletesPhotoObjects() {
		LocalFacilityReportPhotoStorage storage = new LocalFacilityReportPhotoStorage(tempDir);

		var storedPhoto = storage.storeFacilityReportPhoto(new StoreFacilityReportPhotoCommand(
			"report-1",
			"photo.png",
			"image/png",
			new byte[] {1, 2, 3},
			new byte[] {4, 5},
			"0".repeat(64),
			3
		));

		assertThat(storedPhoto.objectKey()).isEqualTo("facility-reports/report-1/%s.png".formatted("0".repeat(64)));
		assertThat(storage.loadFacilityReportPhoto(storedPhoto.objectKey()))
			.hasValueSatisfying(photo -> {
				assertThat(photo.contentType()).isEqualTo("image/png");
				assertThat(photo.bytes()).containsExactly(1, 2, 3);
			});
		assertThat(storage.loadFacilityReportPhoto(storedPhoto.thumbnailObjectKey()))
			.hasValueSatisfying(photo -> assertThat(photo.bytes()).containsExactly(4, 5));

		storage.deleteFacilityReportPhoto(storedPhoto.objectKey());
		storage.deleteFacilityReportPhoto(storedPhoto.thumbnailObjectKey());

		assertThat(storage.loadFacilityReportPhoto(storedPhoto.objectKey())).isEmpty();
		assertThat(storage.loadFacilityReportPhoto(storedPhoto.thumbnailObjectKey())).isEmpty();
	}

	@Test
	void ignoresDeleteKeysOutsideStorageRoot() throws Exception {
		LocalFacilityReportPhotoStorage storage = new LocalFacilityReportPhotoStorage(tempDir.resolve("objects"));
		Path protectedFile = Files.writeString(tempDir.resolve("protected.txt"), "keep");

		storage.deleteFacilityReportPhoto("../protected.txt");

		assertThat(protectedFile).exists();
		assertThat(Files.readString(protectedFile)).isEqualTo("keep");
	}

	@Test
	void preservesWebpContentType() {
		LocalFacilityReportPhotoStorage storage = new LocalFacilityReportPhotoStorage(tempDir);

		var storedPhoto = storage.storeFacilityReportPhoto(new StoreFacilityReportPhotoCommand(
			"report-webp",
			"photo.webp",
			"image/webp",
			new byte[] {1},
			new byte[] {1},
			"1".repeat(64),
			1
		));

		assertThat(storedPhoto.objectKey()).endsWith(".webp");
		assertThat(storage.loadFacilityReportPhoto(storedPhoto.objectKey()))
			.hasValueSatisfying(photo -> assertThat(photo.contentType()).isEqualTo("image/webp"));
	}
}
