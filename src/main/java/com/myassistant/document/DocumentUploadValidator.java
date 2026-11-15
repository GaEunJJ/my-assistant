package com.myassistant.document;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.unit.DataSize;

import java.util.Locale;
import java.util.Set;

/**
 * 문서 업로드 정책(허용 확장자, 최대 파일 크기) 검증기
 * - 파일 내용을 읽기 전에 확장자를 먼저 검사해, 지원하지 않는 파일은 본문을 받지 않고 바로 거절한다
 */
@Component
public class DocumentUploadValidator {

  // DocumentParser가 처리할 수 있는 형식 (프런트 input accept 값과 동일하게 유지)
  private static final Set<String> ALLOWED_EXTENSIONS = Set.of("pdf", "docx", "txt");

  private final DataSize maxFileSize;

  /**
   * 설정값으로 최대 파일 크기를 주입받는다.
   *
   * @param maxFileSize 파일 1개당 최대 크기 (app.upload.max-file-size, 기본 50MB)
   */
  public DocumentUploadValidator(@Value("${app.upload.max-file-size:50MB}") DataSize maxFileSize) {
    this.maxFileSize = maxFileSize;
  }

  /**
   * 파일명의 확장자가 허용 목록에 있는지 검사한다.
   *
   * @param filename 원본 파일명
   * @throws IllegalArgumentException 파일명이 없거나 지원하지 않는 확장자인 경우
   */
  public void validateFilename(String filename) {
    String extension = extractExtension(filename);
    if (!ALLOWED_EXTENSIONS.contains(extension)) {
      throw new IllegalArgumentException("지원하지 않는 파일 형식입니다. PDF, DOCX, TXT 파일만 업로드할 수 있습니다.");
    }
  }

  /**
   * 파일 1개당 허용되는 최대 바이트 수를 반환한다.
   *
   * @return 최대 바이트 수 (DataBufferUtils.join의 제한값으로 사용)
   */
  public int maxBytes() {
    return (int) Math.min(maxFileSize.toBytes(), Integer.MAX_VALUE);
  }

  /**
   * 사용자에게 보여줄 최대 크기 문자열을 반환한다.
   *
   * @return 예: "50MB"
   */
  public String maxFileSizeLabel() {
    return maxFileSize.toMegabytes() + "MB";
  }

  /**
   * 파일명에서 소문자 확장자를 추출한다.
   *
   * @param filename 원본 파일명
   * @return 확장자 (점 제외, 소문자), 확장자가 없으면 빈 문자열
   */
  private String extractExtension(String filename) {
    if (filename == null) {
      return "";
    }
    int dotIndex = filename.lastIndexOf('.');
    if (dotIndex < 0 || dotIndex == filename.length() - 1) {
      return "";
    }
    return filename.substring(dotIndex + 1).toLowerCase(Locale.ROOT);
  }
}
