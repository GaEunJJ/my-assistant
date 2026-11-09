package com.myassistant.document;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 문서 비동기 인덱싱 서비스
 * - 파일 텍스트 추출(페이지 단위) → 청킹 → 임베딩 → PgVector 저장
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentService {

  private final VectorStore vectorStore;
  private final DocumentParser documentParser;

  // 청킹 설정: 500 토큰 단위, 50 토큰 overlap으로 문맥 단절 최소화
  private static final int CHUNK_SIZE = 500;
  private static final int CHUNK_OVERLAP = 50;

  /**
   * 문서를 백그라운드에서 비동기로 인덱싱
   * - 컨트롤러는 즉시 202 반환 후 이 메서드가 별도 스레드에서 실행됨
   *
   * @param file   업로드된 파일
   * @param userId 업로드한 사용자 ID (멀티테넌트 격리용)
   */
  @Async
  public void indexAsync(MultipartFile file, String userId) {
    String filename = file.getOriginalFilename();
    log.info("문서 인덱싱 시작 - userId: {}, file: {}", userId, filename);

    try {
      // 1. 페이지 단위로 텍스트 추출 (PDF는 실제 페이지 번호 보존)
      List<DocumentParser.PageText> pages = documentParser.parsePages(file);

      // 2. 페이지별로 청킹 후 출처 추적용 메타데이터(user_id, filename, page, chunk_index) 태깅
      List<Document> taggedChunks = tagChunks(pages, filename, userId);

      // 3. 임베딩 생성 및 PgVector 저장
      vectorStore.add(taggedChunks);

      log.info("문서 인덱싱 완료 - userId: {}, file: {}, chunks: {}", userId, filename, taggedChunks.size());

    } catch (IOException e) {
      log.error("문서 텍스트 추출 실패 - userId: {}, file: {}", userId, filename, e);
    } catch (Exception e) {
      // 비동기 스레드에서 발생한 예외는 클라이언트로 전달되지 않으므로 반드시 로깅으로 확인
      log.error("문서 인덱싱 실패 - userId: {}, file: {}", userId, filename, e);
    }
  }

  /**
   * 페이지별 텍스트를 청킹하고, 출처 표시에 필요한 메타데이터를 붙인 Document 목록을 만든다.
   * - 페이지 경계를 넘어 청킹되지 않도록 페이지마다 개별적으로 분할한다.
   * - chunk_index는 문서 전체를 기준으로 0부터 순서대로 매긴다.
   *
   * @param pages    페이지 번호와 텍스트 목록
   * @param filename 업로드된 파일명
   * @param userId   업로드한 사용자 ID (멀티테넌트 격리용)
   * @return user_id/filename/page/chunk_index 메타데이터가 태깅된 Document 목록
   */
  private List<Document> tagChunks(List<DocumentParser.PageText> pages, String filename, String userId) {
    TokenTextSplitter splitter = TokenTextSplitter.builder()
        .withChunkSize(CHUNK_SIZE)
        .withMinChunkSizeChars(CHUNK_OVERLAP)
        .withMinChunkLengthToEmbed(5)
        .withMaxNumChunks(10000)
        .withKeepSeparator(true)
        .build();

    List<Document> taggedChunks = new ArrayList<>();
    int chunkIndex = 0;

    for (DocumentParser.PageText page : pages) {
      List<Document> pageChunks = splitter.split(List.of(new Document(page.text())));

      for (Document chunk : pageChunks) {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("user_id", userId);
        metadata.put("filename", filename != null ? filename : "unknown");
        metadata.put("chunk_index", chunkIndex++);
        if (page.pageNumber() != null) {
          metadata.put("page", page.pageNumber());
        }
        taggedChunks.add(new Document(chunk.getText(), metadata));
      }
    }

    return taggedChunks;
  }
}
