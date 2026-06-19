package com.easysubway.report.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.easysubway.report.domain.InvalidFacilityReportException;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("시설 신고 사진 처리기")
class FacilityReportPhotoProcessorTest {

	private static final byte[] VALID_WEBP_BYTES = Base64.getDecoder()
		.decode("UklGRiIAAABXRUJQVlA4IBYAAAAwAQCdASoBAAEADsD+JaQAA3AAAAAA");

	private final FacilityReportPhotoProcessor processor = new FacilityReportPhotoProcessor();

	@Test
	@DisplayName("WebP 신고 사진은 기존 모바일 호환성을 위해 허용하고 metadata chunk를 제거한다")
	void processWebpPhotoAndStripMetadataChunks() {
		byte[] webpWithExif = appendChunk(VALID_WEBP_BYTES, "EXIF", new byte[] {1, 2, 3, 4});

		FacilityReportPhotoAttachment attachment = processor.process(
			"restored-photo.webp",
			"image/webp",
			Base64.getEncoder().encodeToString(webpWithExif)
		);

		assertThat(attachment.fileName()).isEqualTo("restored-photo.webp");
		assertThat(attachment.contentType()).isEqualTo("image/webp");
		assertThat(new String(attachment.storedBytes(), StandardCharsets.ISO_8859_1)).doesNotContain("EXIF");
		assertThat(attachment.thumbnailBytes()).isEqualTo(attachment.storedBytes());
		assertThat(attachment.sha256()).matches("[0-9a-f]{64}");
	}

	@Test
	@DisplayName("이미지 dimension이 너무 큰 첨부는 전체 raster decode 전에 거부한다")
	void rejectOversizedDimensionsBeforeRasterDecode() {
		byte[] oversizedWebp = oversizedVp8xWebp(4_097, 1);

		assertThatThrownBy(() -> processor.process(
			"large.webp",
			"image/webp",
			Base64.getEncoder().encodeToString(oversizedWebp)
		))
			.isInstanceOf(InvalidFacilityReportException.class)
			.hasMessage("사진 이미지 크기를 줄여야 합니다.");
	}

	private byte[] appendChunk(byte[] webpBytes, String chunkType, byte[] chunkData) {
		ByteArrayOutputStream output = new ByteArrayOutputStream();
		output.writeBytes(webpBytes);
		output.writeBytes(chunkType.getBytes(StandardCharsets.US_ASCII));
		writeLittleEndianInt(output, chunkData.length);
		output.writeBytes(chunkData);
		if (chunkData.length % 2 == 1) {
			output.write(0);
		}
		byte[] bytes = output.toByteArray();
		writeLittleEndianInt(bytes, 4, bytes.length - 8);
		return bytes;
	}

	private byte[] oversizedVp8xWebp(int width, int height) {
		ByteArrayOutputStream output = new ByteArrayOutputStream();
		output.writeBytes("RIFF".getBytes(StandardCharsets.US_ASCII));
		writeLittleEndianInt(output, 22);
		output.writeBytes("WEBP".getBytes(StandardCharsets.US_ASCII));
		output.writeBytes("VP8X".getBytes(StandardCharsets.US_ASCII));
		writeLittleEndianInt(output, 10);
		output.write(0);
		output.writeBytes(new byte[] {0, 0, 0});
		writeLittleEndian24(output, width - 1);
		writeLittleEndian24(output, height - 1);
		return output.toByteArray();
	}

	private void writeLittleEndianInt(ByteArrayOutputStream output, int value) {
		output.write(value & 0xff);
		output.write((value >> 8) & 0xff);
		output.write((value >> 16) & 0xff);
		output.write((value >> 24) & 0xff);
	}

	private void writeLittleEndian24(ByteArrayOutputStream output, int value) {
		output.write(value & 0xff);
		output.write((value >> 8) & 0xff);
		output.write((value >> 16) & 0xff);
	}

	private void writeLittleEndianInt(byte[] bytes, int offset, int value) {
		bytes[offset] = (byte) (value & 0xff);
		bytes[offset + 1] = (byte) ((value >> 8) & 0xff);
		bytes[offset + 2] = (byte) ((value >> 16) & 0xff);
		bytes[offset + 3] = (byte) ((value >> 24) & 0xff);
	}
}
