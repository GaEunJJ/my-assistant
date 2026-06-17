package com.myassistant.document;

import org.apache.tika.Tika;
import org.apache.tika.exception.TikaException;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;

/**
 * 업로드된 파일에서 텍스트를 추출하는 파서
 * - Apache Tika를 사용하여 PDF, DOCX, TXT 등 다양한 형식 지원
 */
@Component
public class DocumentParser {

  private final Tika tika = new Tika();

  /**
   * 파일에서 텍스트 추출
   *
   * @param file 업로드된 파일
   * @return 추출된 텍스트
   */
  public String parse(MultipartFile file) throws IOException {
    try (InputStream inputStream = file.getInputStream()) {
      return tika.parseToString(inputStream);
    } catch (TikaException e) {
      throw new IOException("파일 텍스트 추출 실패: " + file.getOriginalFilename(), e);
    }
  }
}
