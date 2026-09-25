package com.easysubway.report.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.easysubway.report.domain.InvalidFacilityReportException;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("시설 신고 사진 처리기")
class FacilityReportPhotoProcessorTest {

	private static final byte[] VALID_WEBP_BYTES = Base64.getDecoder()
		.decode("UklGRiIAAABXRUJQVlA4IBYAAAAwAQCdASoBAAEADsD+JaQAA3AAAAAA");

	private final FacilityReportPhotoProcessor processor = new FacilityReportPhotoProcessor();

	@Test
	@DisplayName("JPEG 신고 사진은 원본을 재작성하고 checksum과 thumbnail을 생성한다")
	void processJpegPhotoAndCreateChecksumAndThumbnail() throws IOException {
		byte[] jpegBytes = encodedImage("jpg", 640, 360);

		FacilityReportPhotoAttachment attachment = processor.process(
			"elevator.jpg",
			"image/jpeg",
			Base64.getEncoder().encodeToString(jpegBytes)
		);

		assertThat(attachment.fileName()).isEqualTo("elevator.jpg");
		assertThat(attachment.contentType()).isEqualTo("image/jpeg");
		assertThat(attachment.storedBytes()).isNotEmpty();
		assertThat(attachment.thumbnailBytes()).isNotEmpty();
		assertThat(attachment.thumbnailBytes().length).isLessThan(attachment.storedBytes().length);
		assertThat(attachment.sha256()).matches("[0-9a-f]{64}");
		assertThat(attachment.sizeBytes()).isEqualTo(attachment.storedBytes().length);
	}

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

	@Test
	@DisplayName("MIME type과 파일 확장자가 맞지 않는 사진은 거부한다")
	void rejectMismatchedExtension() {
		assertThatThrownBy(() -> processor.process(
			"restored-photo.jpg",
			"image/webp",
			Base64.getEncoder().encodeToString(VALID_WEBP_BYTES)
		))
			.isInstanceOf(InvalidFacilityReportException.class)
			.hasMessage("사진 파일 형식을 확인해야 합니다.");
	}

	@Test
	@DisplayName("선언된 MIME type과 magic bytes가 맞지 않는 사진은 거부한다")
	void rejectMismatchedMagicBytes() {
		assertThatThrownBy(() -> processor.process(
			"restored-photo.png",
			"image/png",
			Base64.getEncoder().encodeToString(VALID_WEBP_BYTES)
		))
			.isInstanceOf(InvalidFacilityReportException.class)
			.hasMessage("사진 첨부 정보를 확인해야 합니다.");
	}

	@Test
	@DisplayName("magic bytes만 맞고 image decode가 실패하는 사진은 거부한다")
	void rejectCorruptImageAfterMagicBytes() {
		byte[] corruptPng = new byte[] {
			(byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a,
			0, 0, 0, 0
		};

		assertThatThrownBy(() -> processor.process(
			"corrupt.png",
			"image/png",
			Base64.getEncoder().encodeToString(corruptPng)
		))
			.isInstanceOf(InvalidFacilityReportException.class)
			.hasMessage("사진 첨부 정보를 확인해야 합니다.");
	}

	@Test
	@DisplayName("지원하지 않는 MIME type은 거부한다")
	void rejectUnsupportedContentType() {
		assertThatThrownBy(() -> processor.process(
			"memo.txt",
			"text/plain",
			"aW1hZ2UtYnl0ZXM="
		))
			.isInstanceOf(InvalidFacilityReportException.class)
			.hasMessage("사진 파일 형식을 확인해야 합니다.");
	}

	@Test
	@DisplayName("사진 첨부 필드는 공백을 정상적으로 trim 처리한다")
	void normalizePhotoFields() throws IOException {
		byte[] jpegBytes = encodedImage("jpg", 640, 360);
		FacilityReportPhotoAttachment attachment = processor.process(
			" elevator.jpg ",
			" IMAGE/JPEG ",
			" " + Base64.getEncoder().encodeToString(jpegBytes) + " "
		);
		assertThat(attachment.fileName()).isEqualTo("elevator.jpg");
		assertThat(attachment.contentType()).isEqualTo("image/jpeg");
	}

	@Test
	@DisplayName("Base64 문자열이 너무 길면 거부한다")
	void rejectOversizedBase64Chars() {
		String largePhotoBase64 = "A".repeat(((900 * 1024 + 2) / 3) * 4 + 4);
		assertThatThrownBy(() -> processor.process(
			"large.jpg",
			"image/jpeg",
			largePhotoBase64
		))
			.isInstanceOf(InvalidFacilityReportException.class)
			.hasMessage("사진 파일 크기를 줄여야 합니다.");
	}

	@Test
	@DisplayName("디코딩된 사진 바이트 크기가 900KB를 초과하면 거부한다")
	void rejectOversizedDecodedBytes() {
		String largePhotoBase64 = Base64.getEncoder().encodeToString(new byte[(900 * 1024) + 1]);
		assertThatThrownBy(() -> processor.process(
			"large.jpg",
			"image/jpeg",
			largePhotoBase64
		))
			.isInstanceOf(InvalidFacilityReportException.class)
			.hasMessage("사진 파일 크기를 줄여야 합니다.");
	}

	@Test
	@DisplayName("유효하지 않은 base64 본문은 거부한다")
	void rejectInvalidBase64() {
		assertThatThrownBy(() -> processor.process(
			"broken.jpg",
			"image/jpeg",
			"not-base64"
		))
			.isInstanceOf(InvalidFacilityReportException.class)
			.hasMessage("사진 첨부 정보를 확인해야 합니다.");
	}

	@Test
	@DisplayName("첨부 파일명이나 본문이 비어있으면 거부한다")
	void rejectBlankAttachmentFields() {
		assertThatThrownBy(() -> processor.process(
			"   ",
			"image/jpeg",
			"valid"
		))
			.isInstanceOf(InvalidFacilityReportException.class)
			.hasMessage("사진 첨부 정보를 확인해야 합니다.");
		assertThatThrownBy(() -> processor.process(
			null,
			"image/jpeg",
			"valid"
		))
			.isInstanceOf(InvalidFacilityReportException.class)
			.hasMessage("사진 첨부 정보를 확인해야 합니다.");
		assertThatThrownBy(() -> processor.process(
			"photo.jpg",
			"   ",
			"valid"
		))
			.isInstanceOf(InvalidFacilityReportException.class)
			.hasMessage("사진 첨부 정보를 확인해야 합니다.");
		assertThatThrownBy(() -> processor.process(
			"photo.jpg",
			"image/jpeg",
			"   "
		))
			.isInstanceOf(InvalidFacilityReportException.class)
			.hasMessage("사진 첨부 정보를 확인해야 합니다.");
	}

	@Test
	@DisplayName("hasAnyPhotoField는 하나라도 채워져 있으면 true를 반환한다")
	void hasAnyPhotoFieldChecks() {
		assertThat(processor.hasAnyPhotoField(null, null, null)).isFalse();
		assertThat(processor.hasAnyPhotoField("", " ", "\t")).isFalse();
		assertThat(processor.hasAnyPhotoField("photo.jpg", null, null)).isTrue();
		assertThat(processor.hasAnyPhotoField(null, "image/jpeg", null)).isTrue();
		assertThat(processor.hasAnyPhotoField(null, null, "base64")).isTrue();
	}

	@Test
	@DisplayName("PNG 신고 사진은 원본을 재작성하고 thumbnail을 생성한다")
	void processPngPhoto() throws IOException {
		byte[] pngBytes = encodedImage("png", 320, 240);
		FacilityReportPhotoAttachment attachment = processor.process(
			"elevator.png",
			"image/png",
			Base64.getEncoder().encodeToString(pngBytes)
		);
		assertThat(attachment.fileName()).isEqualTo("elevator.png");
		assertThat(attachment.contentType()).isEqualTo("image/png");
		assertThat(attachment.storedBytes()).isNotEmpty();
		assertThat(attachment.thumbnailBytes()).isNotEmpty();
	}

	@Test
	@DisplayName("processBytes는 raw byte[]를 직접 수신하여 정상 처리한다")
	void processBytesProcessesDirectBytes() throws IOException {
		byte[] jpegBytes = encodedImage("jpg", 320, 240);
		FacilityReportPhotoAttachment attachment = processor.processBytes(
			"elevator.jpg",
			"image/jpeg",
			jpegBytes
		);
		assertThat(attachment.fileName()).isEqualTo("elevator.jpg");
		assertThat(attachment.contentType()).isEqualTo("image/jpeg");
		assertThat(attachment.storedBytes()).isNotEmpty();
	}

	@Test
	@DisplayName("processBytes는 null 또는 빈 바이트 배열을 거부한다")
	void processBytesRejectsNullOrEmptyBytes() {
		assertThatThrownBy(() -> processor.processBytes("elevator.jpg", "image/jpeg", null))
			.isInstanceOf(InvalidFacilityReportException.class)
			.hasMessage("사진 첨부 정보를 확인해야 합니다.");

		assertThatThrownBy(() -> processor.processBytes("elevator.jpg", "image/jpeg", new byte[0]))
			.isInstanceOf(InvalidFacilityReportException.class)
			.hasMessage("사진 첨부 정보를 확인해야 합니다.");
	}

	@Test
	@DisplayName("processBytes는 최대 크기(900KB)를 초과한 바이트 배열을 거부한다")
	void processBytesRejectsOversizedBytes() {
		byte[] oversized = new byte[900 * 1024 + 1];
		assertThatThrownBy(() -> processor.processBytes("elevator.jpg", "image/jpeg", oversized))
			.isInstanceOf(InvalidFacilityReportException.class)
			.hasMessage("사진 파일 크기를 줄여야 합니다.");
	}

	@Test
	@DisplayName("processBytes는 파일명 또는 콘텐츠 유형이 없거나 공백이면 거부한다")
	void processBytesRejectsMissingMetadata() {
		byte[] jpegBytes = new byte[] { (byte) 0xff, (byte) 0xd8, (byte) 0xff };
		assertThatThrownBy(() -> processor.processBytes(null, "image/jpeg", jpegBytes))
			.isInstanceOf(InvalidFacilityReportException.class)
			.hasMessage("사진 첨부 정보를 확인해야 합니다.");

		assertThatThrownBy(() -> processor.processBytes("  ", "image/jpeg", jpegBytes))
			.isInstanceOf(InvalidFacilityReportException.class)
			.hasMessage("사진 첨부 정보를 확인해야 합니다.");

		assertThatThrownBy(() -> processor.processBytes("elevator.jpg", null, jpegBytes))
			.isInstanceOf(InvalidFacilityReportException.class)
			.hasMessage("사진 첨부 정보를 확인해야 합니다.");

		assertThatThrownBy(() -> processor.processBytes("elevator.jpg", "   ", jpegBytes))
			.isInstanceOf(InvalidFacilityReportException.class)
			.hasMessage("사진 첨부 정보를 확인해야 합니다.");
	}

	@Test
	@DisplayName("processBytes는 webp 형식 바이트를 정상 처리한다")
	void processBytesProcessesWebp() {
		byte[] webpBytes = VALID_WEBP_BYTES;
		FacilityReportPhotoAttachment attachment = processor.processBytes(
			"elevator.webp",
			"image/webp",
			webpBytes
		);
		assertThat(attachment.fileName()).isEqualTo("elevator.webp");
		assertThat(attachment.contentType()).isEqualTo("image/webp");
		assertThat(attachment.storedBytes()).isNotEmpty();
	}

	@Test
	@DisplayName("PhotoMediaType은 canonical 미디어 타입과 확장을 올바르게 반환하고 비교한다")
	void photoMediaTypeCanonicalAndExtension() {
		com.easysubway.report.domain.FacilityReport.PhotoMediaType jpeg =
			com.easysubway.report.domain.FacilityReport.PhotoMediaType.from("IMAGE/JPEG");
		assertThat(jpeg.canonicalValue()).isEqualTo("image/jpeg");
		assertThat(jpeg.extension()).isEqualTo(".jpg");
		assertThat(jpeg).isEqualTo(com.easysubway.report.domain.FacilityReport.PhotoMediaType.IMAGE_JPEG);
		assertThat(jpeg.hashCode()).isEqualTo(com.easysubway.report.domain.FacilityReport.PhotoMediaType.IMAGE_JPEG.hashCode());
		assertThat(jpeg.toString()).isEqualTo("image/jpeg");
		assertThat(jpeg.equals(jpeg)).isTrue();
		assertThat(jpeg.equals(null)).isFalse();
		assertThat(jpeg.equals("other")).isFalse();

		com.easysubway.report.domain.FacilityReport.PhotoMediaType png =
			com.easysubway.report.domain.FacilityReport.PhotoMediaType.from("image/png");
		assertThat(png.canonicalValue()).isEqualTo("image/png");
		assertThat(png.extension()).isEqualTo(".png");
		assertThat(jpeg.equals(png)).isFalse();

		com.easysubway.report.domain.FacilityReport.PhotoMediaType webp =
			com.easysubway.report.domain.FacilityReport.PhotoMediaType.from("IMAGE/WEBP");
		assertThat(webp.canonicalValue()).isEqualTo("image/webp");
		assertThat(webp.extension()).isEqualTo(".webp");
	}

	@Test
	@DisplayName("PhotoMediaType은 파라미터, 와일드카드, 빈 문자열, 지원하지 않는 타입을 거부한다")
	void photoMediaTypeRejectsInvalidInputs() {
		assertThatThrownBy(() -> com.easysubway.report.domain.FacilityReport.PhotoMediaType.from(null))
			.isInstanceOf(InvalidFacilityReportException.class);
		assertThatThrownBy(() -> com.easysubway.report.domain.FacilityReport.PhotoMediaType.from(""))
			.isInstanceOf(InvalidFacilityReportException.class);
		assertThatThrownBy(() -> com.easysubway.report.domain.FacilityReport.PhotoMediaType.from("   "))
			.isInstanceOf(InvalidFacilityReportException.class);
		assertThatThrownBy(() -> com.easysubway.report.domain.FacilityReport.PhotoMediaType.from("image/jpeg; charset=utf-8"))
			.isInstanceOf(InvalidFacilityReportException.class);
		assertThatThrownBy(() -> com.easysubway.report.domain.FacilityReport.PhotoMediaType.from("image/*"))
			.isInstanceOf(InvalidFacilityReportException.class);
		assertThatThrownBy(() -> com.easysubway.report.domain.FacilityReport.PhotoMediaType.from("image/gif"))
			.isInstanceOf(InvalidFacilityReportException.class);
	}


	private byte[] encodedImage(String formatName, int width, int height) throws IOException {
		BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
		for (int y = 0; y < height; y++) {
			for (int x = 0; x < width; x++) {
				image.setRGB(x, y, (x + y) % 2 == 0 ? Color.WHITE.getRGB() : Color.LIGHT_GRAY.getRGB());
			}
		}
		ByteArrayOutputStream output = new ByteArrayOutputStream();
		ImageIO.write(image, formatName, output);
		return output.toByteArray();
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
