package com.easysubway.report.adapter.out.storage;

import com.easysubway.report.application.port.out.DeleteFacilityReportPhotoPort;
import com.easysubway.report.application.port.out.LoadFacilityReportPhotoPort;
import com.easysubway.report.application.port.out.LoadFacilityReportPhotoPort.LoadedFacilityReportPhoto;
import com.easysubway.report.application.port.out.StoreFacilityReportPhotoPort;
import com.easysubway.report.application.port.out.StoreFacilityReportUploadedPhotoPort;
import com.easysubway.report.application.port.out.StoreFacilityReportUploadedPhotoPort.StoreUploadedReportPhotoCommand;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("!prod")
public class LocalFacilityReportPhotoStorage implements
	StoreFacilityReportPhotoPort,
	LoadFacilityReportPhotoPort,
	DeleteFacilityReportPhotoPort,
	StoreFacilityReportUploadedPhotoPort {

	private final Path storageRoot;

	public LocalFacilityReportPhotoStorage(
		@Value("${easysubway.reports.photos.storage-dir:${java.io.tmpdir}/easysubway-report-photos}") Path storageRoot
	) {
		this.storageRoot = storageRoot;
	}

	@Override
	public StoredFacilityReportPhoto storeFacilityReportPhoto(StoreFacilityReportPhotoCommand command) {
		String extension = extensionFor(command.contentType());
		String objectKey = "facility-reports/%s/%s%s".formatted(command.reportId(), command.sha256(), extension);
		String thumbnailObjectKey = "facility-reports/%s/%s-thumbnail%s".formatted(
			command.reportId(),
			command.sha256(),
			extension
		);
		writeObject(objectKey, command.storedBytes());
		writeObject(thumbnailObjectKey, command.thumbnailBytes());
		return new StoredFacilityReportPhoto(objectKey, thumbnailObjectKey);
	}

	@Override
	public void storeUploadedReportPhoto(StoreUploadedReportPhotoCommand command) {
		writeObject(command.objectKey(), command.bytes());
	}

	@Override
	public void deleteFacilityReportPhoto(String objectKey) {
		if (objectKey == null || objectKey.isBlank()) {
			return;
		}
		Path objectPath = storageRoot.resolve(objectKey).normalize();
		if (!objectPath.startsWith(storageRoot.normalize())) {
			return;
		}
		try {
			Files.deleteIfExists(objectPath);
		} catch (IOException exception) {
			throw new UncheckedIOException("Failed to delete facility report photo object", exception);
		}
	}

	@Override
	public Optional<LoadedFacilityReportPhoto> loadFacilityReportPhoto(String objectKey) {
		if (objectKey == null || objectKey.isBlank()) {
			return Optional.empty();
		}
		Path objectPath = storageRoot.resolve(objectKey).normalize();
		if (!objectPath.startsWith(storageRoot.normalize()) || !Files.exists(objectPath)) {
			return Optional.empty();
		}
		try {
			return Optional.of(new LoadedFacilityReportPhoto(contentTypeFor(objectKey), Files.readAllBytes(objectPath)));
		} catch (IOException exception) {
			throw new UncheckedIOException("Failed to load facility report photo object", exception);
		}
	}

	private void writeObject(String objectKey, byte[] bytes) {
		Path objectPath = storageRoot.resolve(objectKey).normalize();
		if (!objectPath.startsWith(storageRoot.normalize())) {
			throw new IllegalArgumentException("Invalid facility report photo object key");
		}
		try {
			Files.createDirectories(objectPath.getParent());
			Files.write(objectPath, bytes);
		} catch (IOException exception) {
			throw new UncheckedIOException("Failed to store facility report photo object", exception);
		}
	}

	private String extensionFor(String contentType) {
		return switch (contentType) {
			case "image/png" -> ".png";
			case "image/webp" -> ".webp";
			default -> ".jpg";
		};
	}

	private String contentTypeFor(String objectKey) {
		if (objectKey.endsWith(".png")) {
			return "image/png";
		}
		if (objectKey.endsWith(".webp")) {
			return "image/webp";
		}
		return "image/jpeg";
	}
}
