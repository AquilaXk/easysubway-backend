package com.easysubway.report.application.port.out;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class FacilityReportPhotoPortValueTest {
	@Test
	void photoByteValuesPreserveNullAndDefensivelyCopy() {
		byte[] bytes = {1, 2};
		var loaded = new LoadFacilityReportPhotoPort.LoadedFacilityReportPhoto("image/jpeg", bytes);
		bytes[0] = 9;
		assertThat(loaded.bytes()).containsExactly(1, 2);
		loaded.bytes()[1] = 8;
		assertThat(loaded.bytes()).containsExactly(1, 2);
		assertThat(new LoadFacilityReportPhotoPort.LoadedFacilityReportPhoto("image/jpeg", null).bytes()).isNull();

		byte[] stored = {3}, thumbnail = {4};
		var command = new StoreFacilityReportPhotoPort.StoreFacilityReportPhotoCommand("id", "name", "image/jpeg", stored, thumbnail, "hash", 1);
		stored[0] = 9;
		thumbnail[0] = 9;
		assertThat(command.storedBytes()).containsExactly(3);
		assertThat(command.thumbnailBytes()).containsExactly(4);
		command.storedBytes()[0] = 8;
		command.thumbnailBytes()[0] = 8;
		assertThat(command.storedBytes()).containsExactly(3);
		assertThat(command.thumbnailBytes()).containsExactly(4);
		var nullCommand = new StoreFacilityReportPhotoPort.StoreFacilityReportPhotoCommand("id", "name", "image/jpeg", null, null, "hash", 1);
		assertThat(nullCommand.storedBytes()).isNull();
		assertThat(nullCommand.thumbnailBytes()).isNull();

		byte[] uploaded = {5};
		var upload = new StoreFacilityReportUploadedPhotoPort.StoreUploadedReportPhotoCommand("key", uploaded);
		uploaded[0] = 9;
		assertThat(upload.bytes()).containsExactly(5);
		upload.bytes()[0] = 8;
		assertThat(upload.bytes()).containsExactly(5);
		assertThat(new StoreFacilityReportUploadedPhotoPort.StoreUploadedReportPhotoCommand("key", null).bytes()).isNull();
	}
}
