package com.myassistant.document;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.tika.Tika;
import org.apache.tika.exception.TikaException;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
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
   * 파일에서 페이지 단위로 텍스트를 추출한다.
   * - PDF: 실제 페이지 번호(1부터 시작)를 보존하여 페이지별로 분리 추출
   * - 그 외 형식(DOCX, TXT 등): 페이지 개념이 없으므로 전체 텍스트를 페이지 번호 없이 1건으로 반환
   *
   * @param file 업로드된 파일
   * @return 페이지 번호와 텍스트로 구성된 목록
   * @throws IOException 텍스트 추출에 실패한 경우
   */
  public List<PageText> parsePages(MultipartFile file) throws IOException {
    if (isPdf(file)) {
      return parsePdfPages(file);
    }
    return List.of(new PageText(null, parseWholeText(file)));
  }

  /**
   * 파일명 확장자로 PDF 여부를 판단한다.
   *
   * @param file 업로드된 파일
   * @return 확장자가 .pdf이면 true
   */
  private boolean isPdf(MultipartFile file) {
    String filename = file.getOriginalFilename();
    return filename != null && filename.toLowerCase(Locale.ROOT).endsWith(".pdf");
  }

  /**
   * PDFBox로 PDF를 페이지 단위로 파싱한다.
   *
   * @param file 업로드된 PDF 파일
   * @return 1부터 시작하는 페이지 번호가 매겨진 페이지별 텍스트 목록
   * @throws IOException PDF 로드 또는 텍스트 추출에 실패한 경우
   */
  private List<PageText> parsePdfPages(MultipartFile file) throws IOException {
    try (InputStream inputStream = file.getInputStream();
        PDDocument document = PDDocument.load(inputStream)) {
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
   * @param file 업로드된 파일
   * @return 추출된 전체 텍스트
   * @throws IOException 텍스트 추출에 실패한 경우
   */
  private String parseWholeText(MultipartFile file) throws IOException {
    try (InputStream inputStream = file.getInputStream()) {
      return tika.parseToString(inputStream);
    } catch (TikaException e) {
      throw new IOException("파일 텍스트 추출 실패: " + file.getOriginalFilename(), e);
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
