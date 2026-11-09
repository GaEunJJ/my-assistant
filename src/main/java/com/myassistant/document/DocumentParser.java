package com.myassistant.document;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.tika.Tika;
import org.apache.tika.exception.TikaException;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 업로드된 파일에서 텍스트를 추출하는 파서
 * - Apache Tika를 사용하여 PDF, DOCX, TXT 등 다양한 형식 지원
 * - PDF는 PDFBox로 페이지 단위로 추출하여, 출처 표시 시 실제 페이지 번호를 남길 수 있도록 한다
 */
@Component
public class DocumentParser {

  private final Tika tika = new Tika();

  /**
   * 파일 바이트 배열에서 페이지 단위로 텍스트를 추출한다.
   * - PDF: 실제 페이지 번호(1부터 시작)를 보존하여 페이지별로 분리 추출
   * - 그 외 형식(DOCX, TXT 등): 페이지 개념이 없으므로 전체 텍스트를 페이지 번호 없이 1건으로 반환
   *
   * @param content  파일 전체 바이트 배열
   * @param filename 원본 파일명 (확장자로 PDF 여부 판단)
   * @return 페이지 번호와 텍스트로 구성된 목록
   * @throws IOException 텍스트 추출에 실패한 경우
   */
  public List<PageText> parsePages(byte[] content, String filename) throws IOException {
    if (isPdf(filename)) {
      return parsePdfPages(content);
    }
    return List.of(new PageText(null, parseWholeText(content)));
  }

  /**
   * 파일명 확장자로 PDF 여부를 판단한다.
   *
   * @param filename 원본 파일명
   * @return 확장자가 .pdf이면 true
   */
  private boolean isPdf(String filename) {
    return filename != null && filename.toLowerCase(Locale.ROOT).endsWith(".pdf");
  }

  /**
   * PDFBox로 PDF를 페이지 단위로 파싱한다.
   *
   * @param content PDF 파일 전체 바이트 배열
   * @return 1부터 시작하는 페이지 번호가 매겨진 페이지별 텍스트 목록
   * @throws IOException PDF 로드 또는 텍스트 추출에 실패한 경우
   */
  private List<PageText> parsePdfPages(byte[] content) throws IOException {
    try (PDDocument document = PDDocument.load(new ByteArrayInputStream(content))) {
      PDFTextStripper stripper = new PDFTextStripper();
      List<PageText> pages = new ArrayList<>();

      // 페이지마다 시작/끝 페이지를 동일하게 좁혀서 추출 — 페이지 경계를 보존하기 위함
      for (int pageNumber = 1; pageNumber <= document.getNumberOfPages(); pageNumber++) {
        stripper.setStartPage(pageNumber);
        stripper.setEndPage(pageNumber);
        pages.add(new PageText(pageNumber, stripper.getText(document)));
      }
      return pages;
    }
  }

  /**
   * PDF 외 형식에서 Tika로 전체 텍스트를 추출한다.
   *
   * @param content 파일 전체 바이트 배열
   * @return 추출된 전체 텍스트
   * @throws IOException 텍스트 추출에 실패한 경우
   */
  private String parseWholeText(byte[] content) throws IOException {
    try {
      return tika.parseToString(new ByteArrayInputStream(content));
    } catch (TikaException e) {
      throw new IOException("파일 텍스트 추출 실패", e);
    }
  }

  /**
   * 페이지 번호와 추출된 텍스트를 담는 레코드.
   *
   * @param pageNumber 1부터 시작하는 페이지 번호, 페이지 개념이 없는 형식이면 null
   * @param text       해당 페이지에서 추출된 텍스트
   */
  public record PageText(Integer pageNumber, String text) {
  }
}
