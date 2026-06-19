package com.easysubway.report.application.service;

import com.easysubway.report.domain.InvalidFacilityReportException;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.Locale;
import java.util.Set;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;

final class FacilityReportPhotoProcessor {

	static final int MAX_PHOTO_BYTES = 900 * 1024;
	private static final int MAX_PHOTO_BASE64_CHARS = ((MAX_PHOTO_BYTES + 2) / 3) * 4;
	private static final int MAX_PHOTO_WIDTH = 4_096;
	private static final int MAX_PHOTO_HEIGHT = 4_096;
	private static final long MAX_PHOTO_PIXELS = 12_000_000;
	private static final int THUMBNAIL_MAX_SIDE = 320;
	private static final Set<String> ALLOWED_PHOTO_CONTENT_TYPES = Set.of(
		"image/jpeg",
		"image/png",
		"image/webp"
	);

	FacilityReportPhotoAttachment process(String fileName, String contentType, String photoBase64) {
		validateCompleteAttachment(fileName, contentType, photoBase64);
		String normalizedContentType = normalizeContentType(contentType);
		String normalizedFileName = normalizeFileName(fileName);
		requireAllowedExtension(normalizedFileName, normalizedContentType);

		byte[] decodedBytes = decode(photoBase64);
		requireSupportedMagic(decodedBytes, normalizedContentType);
		requireAllowedDimensions(readDimensions(decodedBytes, normalizedContentType));
		byte[] sanitizedBytes;
		byte[] thumbnailBytes;
		if ("image/webp".equals(normalizedContentType)) {
			sanitizedBytes = stripWebpMetadata(decodedBytes);
			thumbnailBytes = sanitizedBytes;
		} else {
			BufferedImage image = readImage(decodedBytes);
			sanitizedBytes = rewriteImage(image, normalizedContentType);
			thumbnailBytes = rewriteImage(thumbnail(image), normalizedContentType);
		}

		if (sanitizedBytes.length > MAX_PHOTO_BYTES) {
			throw new InvalidFacilityReportException("사진 파일 크기를 줄여야 합니다.");
		}
		return new FacilityReportPhotoAttachment(
			normalizedFileName,
			normalizedContentType,
			sanitizedBytes,
			thumbnailBytes,
			sha256(sanitizedBytes),
			sanitizedBytes.length
		);
	}

	boolean hasAnyPhotoField(String fileName, String contentType, String photoBase64) {
		return hasText(fileName) || hasText(contentType) || hasText(photoBase64);
	}

	private void validateCompleteAttachment(String fileName, String contentType, String photoBase64) {
		if (!hasText(fileName) || !hasText(contentType) || !hasText(photoBase64)) {
			throw new InvalidFacilityReportException("사진 첨부 정보를 확인해야 합니다.");
		}
	}

	private String normalizeContentType(String contentType) {
		String normalized = contentType.trim().toLowerCase(Locale.ROOT);
		if (!ALLOWED_PHOTO_CONTENT_TYPES.contains(normalized)) {
			throw new InvalidFacilityReportException("사진 파일 형식을 확인해야 합니다.");
		}
		return normalized;
	}

	private String normalizeFileName(String fileName) {
		String normalized = fileName.trim();
		if (normalized.isEmpty()) {
			throw new InvalidFacilityReportException("사진 첨부 정보를 확인해야 합니다.");
		}
		return normalized;
	}

	private void requireAllowedExtension(String fileName, String contentType) {
		String lowerName = fileName.toLowerCase(Locale.ROOT);
		boolean extensionMatches = switch (contentType) {
			case "image/jpeg" -> lowerName.endsWith(".jpg") || lowerName.endsWith(".jpeg");
			case "image/png" -> lowerName.endsWith(".png");
			case "image/webp" -> lowerName.endsWith(".webp");
			default -> false;
		};
		if (!extensionMatches) {
			throw new InvalidFacilityReportException("사진 파일 형식을 확인해야 합니다.");
		}
	}

	private byte[] decode(String photoBase64) {
		String normalized = photoBase64.trim();
		if (normalized.length() > MAX_PHOTO_BASE64_CHARS) {
			throw new InvalidFacilityReportException("사진 파일 크기를 줄여야 합니다.");
		}
		try {
			byte[] decodedBytes = Base64.getDecoder().decode(normalized);
			if (decodedBytes.length > MAX_PHOTO_BYTES) {
				throw new InvalidFacilityReportException("사진 파일 크기를 줄여야 합니다.");
			}
			return decodedBytes;
		} catch (IllegalArgumentException exception) {
			throw new InvalidFacilityReportException("사진 첨부 정보를 확인해야 합니다.");
		}
	}

	private void requireSupportedMagic(byte[] bytes, String contentType) {
		boolean matches = switch (contentType) {
			case "image/jpeg" -> bytes.length >= 3
				&& (bytes[0] & 0xff) == 0xff
				&& (bytes[1] & 0xff) == 0xd8
				&& (bytes[2] & 0xff) == 0xff;
			case "image/png" -> bytes.length >= 8
				&& (bytes[0] & 0xff) == 0x89
				&& bytes[1] == 0x50
				&& bytes[2] == 0x4e
				&& bytes[3] == 0x47
				&& bytes[4] == 0x0d
				&& bytes[5] == 0x0a
				&& bytes[6] == 0x1a
				&& bytes[7] == 0x0a;
			case "image/webp" -> isRiffWebp(bytes);
			default -> false;
		};
		if (!matches) {
			throw new InvalidFacilityReportException("사진 첨부 정보를 확인해야 합니다.");
		}
	}

	private PhotoDimensions readDimensions(byte[] bytes, String contentType) {
		if ("image/webp".equals(contentType)) {
			return readWebpDimensions(bytes);
		}
		try (ImageInputStream input = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
			if (input == null) {
				throw new InvalidFacilityReportException("사진 첨부 정보를 확인해야 합니다.");
			}
			Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
			if (!readers.hasNext()) {
				throw new InvalidFacilityReportException("사진 첨부 정보를 확인해야 합니다.");
			}
			ImageReader reader = readers.next();
			try {
				reader.setInput(input, true, true);
				return new PhotoDimensions(reader.getWidth(0), reader.getHeight(0));
			} finally {
				reader.dispose();
			}
		} catch (IOException exception) {
			throw new InvalidFacilityReportException("사진 첨부 정보를 확인해야 합니다.");
		}
	}

	private void requireAllowedDimensions(PhotoDimensions dimensions) {
		if (dimensions.width() <= 0
			|| dimensions.height() <= 0
			|| dimensions.width() > MAX_PHOTO_WIDTH
			|| dimensions.height() > MAX_PHOTO_HEIGHT
			|| (long) dimensions.width() * dimensions.height() > MAX_PHOTO_PIXELS) {
			throw new InvalidFacilityReportException("사진 이미지 크기를 줄여야 합니다.");
		}
	}

	private BufferedImage readImage(byte[] bytes) {
		try {
			BufferedImage image = ImageIO.read(new ByteArrayInputStream(bytes));
			if (image == null || image.getWidth() <= 0 || image.getHeight() <= 0) {
				throw new InvalidFacilityReportException("사진 첨부 정보를 확인해야 합니다.");
			}
			return image;
		} catch (IOException exception) {
			throw new InvalidFacilityReportException("사진 첨부 정보를 확인해야 합니다.");
		}
	}

	private PhotoDimensions readWebpDimensions(byte[] bytes) {
		int offset = 12;
		while (offset + 8 <= bytes.length) {
			String chunkType = chunkType(bytes, offset);
			int chunkSize = chunkSize(bytes, offset);
			int dataOffset = offset + 8;
			requireChunkBounds(bytes, dataOffset, chunkSize);
			switch (chunkType) {
				case "VP8 " -> {
					return readVp8Dimensions(bytes, dataOffset, chunkSize);
				}
				case "VP8L" -> {
					return readVp8LosslessDimensions(bytes, dataOffset, chunkSize);
				}
				case "VP8X" -> {
					return readVp8ExtendedDimensions(bytes, dataOffset, chunkSize);
				}
				default -> offset = nextChunkOffset(dataOffset, chunkSize);
			}
		}
		throw new InvalidFacilityReportException("사진 첨부 정보를 확인해야 합니다.");
	}

	private PhotoDimensions readVp8Dimensions(byte[] bytes, int dataOffset, int chunkSize) {
		if (chunkSize < 10
			|| bytes[dataOffset + 3] != (byte) 0x9d
			|| bytes[dataOffset + 4] != 0x01
			|| bytes[dataOffset + 5] != 0x2a) {
			throw new InvalidFacilityReportException("사진 첨부 정보를 확인해야 합니다.");
		}
		int width = littleEndianUnsignedShort(bytes, dataOffset + 6) & 0x3fff;
		int height = littleEndianUnsignedShort(bytes, dataOffset + 8) & 0x3fff;
		return new PhotoDimensions(width, height);
	}

	private PhotoDimensions readVp8LosslessDimensions(byte[] bytes, int dataOffset, int chunkSize) {
		if (chunkSize < 5 || bytes[dataOffset] != 0x2f) {
			throw new InvalidFacilityReportException("사진 첨부 정보를 확인해야 합니다.");
		}
		int bits = (bytes[dataOffset + 1] & 0xff)
			| ((bytes[dataOffset + 2] & 0xff) << 8)
			| ((bytes[dataOffset + 3] & 0xff) << 16)
			| ((bytes[dataOffset + 4] & 0xff) << 24);
		int width = (bits & 0x3fff) + 1;
		int height = ((bits >> 14) & 0x3fff) + 1;
		return new PhotoDimensions(width, height);
	}

	private PhotoDimensions readVp8ExtendedDimensions(byte[] bytes, int dataOffset, int chunkSize) {
		if (chunkSize < 10) {
			throw new InvalidFacilityReportException("사진 첨부 정보를 확인해야 합니다.");
		}
		int width = littleEndian24(bytes, dataOffset + 4) + 1;
		int height = littleEndian24(bytes, dataOffset + 7) + 1;
		return new PhotoDimensions(width, height);
	}

	private byte[] stripWebpMetadata(byte[] bytes) {
		ByteArrayOutputStream output = new ByteArrayOutputStream(bytes.length);
		output.write(bytes, 0, 12);
		int offset = 12;
		while (offset + 8 <= bytes.length) {
			String chunkType = chunkType(bytes, offset);
			int chunkSize = chunkSize(bytes, offset);
			int dataOffset = offset + 8;
			requireChunkBounds(bytes, dataOffset, chunkSize);
			int nextOffset = nextChunkOffset(dataOffset, chunkSize);
			if (!"EXIF".equals(chunkType) && !"XMP ".equals(chunkType)) {
				output.write(bytes, offset, nextOffset - offset);
			}
			offset = nextOffset;
		}
		if (offset != bytes.length) {
			throw new InvalidFacilityReportException("사진 첨부 정보를 확인해야 합니다.");
		}
		byte[] sanitizedBytes = output.toByteArray();
		writeLittleEndianInt(sanitizedBytes, 4, sanitizedBytes.length - 8);
		clearWebpMetadataFlags(sanitizedBytes);
		return sanitizedBytes;
	}

	private void clearWebpMetadataFlags(byte[] bytes) {
		if (bytes.length >= 30 && "VP8X".equals(chunkType(bytes, 12))) {
			bytes[20] = (byte) (bytes[20] & ~0x0c);
		}
	}

	private boolean isRiffWebp(byte[] bytes) {
		return bytes.length >= 20
			&& bytes[0] == 0x52
			&& bytes[1] == 0x49
			&& bytes[2] == 0x46
			&& bytes[3] == 0x46
			&& bytes[8] == 0x57
			&& bytes[9] == 0x45
			&& bytes[10] == 0x42
			&& bytes[11] == 0x50;
	}

	private String chunkType(byte[] bytes, int offset) {
		return new String(bytes, offset, 4, java.nio.charset.StandardCharsets.US_ASCII);
	}

	private int chunkSize(byte[] bytes, int offset) {
		long size = (bytes[offset + 4] & 0xffL)
			| ((bytes[offset + 5] & 0xffL) << 8)
			| ((bytes[offset + 6] & 0xffL) << 16)
			| ((bytes[offset + 7] & 0xffL) << 24);
		if (size > Integer.MAX_VALUE) {
			throw new InvalidFacilityReportException("사진 첨부 정보를 확인해야 합니다.");
		}
		return (int) size;
	}

	private void requireChunkBounds(byte[] bytes, int dataOffset, int chunkSize) {
		if (chunkSize < 0 || dataOffset > bytes.length || dataOffset + chunkSize > bytes.length) {
			throw new InvalidFacilityReportException("사진 첨부 정보를 확인해야 합니다.");
		}
	}

	private int nextChunkOffset(int dataOffset, int chunkSize) {
		return dataOffset + chunkSize + (chunkSize % 2);
	}

	private int littleEndianUnsignedShort(byte[] bytes, int offset) {
		return (bytes[offset] & 0xff) | ((bytes[offset + 1] & 0xff) << 8);
	}

	private int littleEndian24(byte[] bytes, int offset) {
		return (bytes[offset] & 0xff) | ((bytes[offset + 1] & 0xff) << 8) | ((bytes[offset + 2] & 0xff) << 16);
	}

	private void writeLittleEndianInt(byte[] bytes, int offset, int value) {
		bytes[offset] = (byte) (value & 0xff);
		bytes[offset + 1] = (byte) ((value >> 8) & 0xff);
		bytes[offset + 2] = (byte) ((value >> 16) & 0xff);
		bytes[offset + 3] = (byte) ((value >> 24) & 0xff);
	}

	private byte[] rewriteImage(BufferedImage image, String contentType) {
		try {
			ByteArrayOutputStream output = new ByteArrayOutputStream();
			String formatName = "image/png".equals(contentType) ? "png" : "jpg";
			BufferedImage writable = ensureWritableRgb(image, contentType);
			if (!ImageIO.write(writable, formatName, output)) {
				throw new InvalidFacilityReportException("사진 첨부 정보를 확인해야 합니다.");
			}
			return output.toByteArray();
		} catch (IOException exception) {
			throw new InvalidFacilityReportException("사진 첨부 정보를 확인해야 합니다.");
		}
	}

	private BufferedImage ensureWritableRgb(BufferedImage image, String contentType) {
		int targetType = "image/png".equals(contentType) ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB;
		if (image.getType() == targetType) {
			return image;
		}
		BufferedImage converted = new BufferedImage(image.getWidth(), image.getHeight(), targetType);
		Graphics2D graphics = converted.createGraphics();
		try {
			graphics.drawImage(image, 0, 0, null);
		} finally {
			graphics.dispose();
		}
		return converted;
	}

	private BufferedImage thumbnail(BufferedImage image) {
		int width = image.getWidth();
		int height = image.getHeight();
		int maxSide = Math.max(width, height);
		if (maxSide <= THUMBNAIL_MAX_SIDE) {
			return image;
		}
		double ratio = THUMBNAIL_MAX_SIDE / (double) maxSide;
		int thumbnailWidth = Math.max(1, (int) Math.round(width * ratio));
		int thumbnailHeight = Math.max(1, (int) Math.round(height * ratio));
		BufferedImage thumbnail = new BufferedImage(thumbnailWidth, thumbnailHeight, BufferedImage.TYPE_INT_RGB);
		Graphics2D graphics = thumbnail.createGraphics();
		try {
			graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
			graphics.drawImage(image, 0, 0, thumbnailWidth, thumbnailHeight, null);
		} finally {
			graphics.dispose();
		}
		return thumbnail;
	}

	private String sha256(byte[] bytes) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
		} catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("SHA-256 digest is not available", exception);
		}
	}

	private boolean hasText(String value) {
		return value != null && !value.isBlank();
	}

	private record PhotoDimensions(int width, int height) {
	}
}
