package com.myassistant.document;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 문서 비동기 인덱싱 서비스
 * - 파일 텍스트 추출(페이지 단위) → 청킹 → 임베딩 → PgVector 저장
 * - 진행 상황을 documents 테이블의 status(PENDING → INDEXING → DONE/FAILED)로 기록한다
 * - @Async는 같은 클래스 내부 호출 시 프록시를 거치지 않아 무시되므로, 호출부(DocumentService)와 클래스를 분리한다
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentIndexingService {

  private final VectorStore vectorStore;
  private final DocumentParser documentParser;
  private final UploadedDocumentRepository documentRepository;

  // 청킹 설정: 500 토큰 단위, 50 토큰 overlap으로 문맥 단절 최소화
  private static final int CHUNK_SIZE = 500;
  private static final int CHUNK_OVERLAP = 50;

  // documents.error_message 컬럼 길이
  private static final int MAX_ERROR_MESSAGE_LENGTH = 500;

  /**
   * 문서를 백그라운드에서 비동기로 인덱싱하고 결과를 상태로 기록한다.
   * - @Async 스레드풀(이벤트 루프가 아닌 스레드)에서 실행되므로 R2DBC 상태 갱신에 block()을 사용해도 된다.
   *
   * @param document    PENDING 상태로 저장된 문서 엔티티
   * @param fileContent 업로드된 파일의 전체 바이트 배열
   */
  @Async
  public void indexAsync(UploadedDocument document, byte[] fileContent) {
    String userId = document.getUserId();
    String filename = document.getFilename();
    log.info("문서 인덱싱 시작 - userId: {}, documentId: {}, file: {}", userId, document.getId(), filename);

    UploadedDocument indexing = documentRepository.save(document.withStatus(DocumentStatus.INDEXING)).block();

    try {
      // 1. 페이지 단위로 텍스트 추출 (PDF는 실제 페이지 번호 보존)
      List<DocumentParser.PageText> pages = documentParser.parsePages(fileContent, filename);

      // 2. 페이지별로 청킹 후 출처 추적/삭제용 메타데이터(user_id, document_id, filename, page, chunk_index) 태깅
      List<Document> taggedChunks = tagChunks(pages, indexing);

      // 스캔 이미지 PDF 등 텍스트가 없는 문서는 검색 대상이 없으므로 실패로 처리
      if (taggedChunks.isEmpty()) {
        markFailed(indexing, "문서에서 추출된 텍스트가 없습니다. (스캔 이미지 문서는 지원하지 않습니다)");
        return;
      }

      // 3. 임베딩 생성 및 PgVector 저장
      vectorStore.add(taggedChunks);

      documentRepository.save(indexing.withStatus(DocumentStatus.DONE).toBuilder()
          .chunkCount(taggedChunks.size())
          .build()).block();

      log.info("문서 인덱싱 완료 - userId: {}, documentId: {}, chunks: {}", userId, indexing.getId(), taggedChunks.size());

    } catch (IOException e) {
      log.error("문서 텍스트 추출 실패 - userId: {}, documentId: {}", userId, indexing.getId(), e);
      markFailed(indexing, "파일에서 텍스트를 추출하지 못했습니다. 파일이 손상되었거나 지원하지 않는 형식입니다.");
    } catch (Exception e) {
      // 비동기 스레드에서 발생한 예외는 클라이언트로 전달되지 않으므로 로그와 상태로 남긴다
      // 내부 오류 상세(스택, API 키 관련 메시지 등)는 사용자에게 노출하지 않는다
      log.error("문서 인덱싱 실패 - userId: {}, documentId: {}", userId, indexing.getId(), e);
      markFailed(indexing, "인덱싱 중 오류가 발생했습니다. 잠시 후 다시 업로드해주세요.");
    }
  }

  /**
   * 문서를 FAILED 상태로 기록한다. 상태 저장 자체가 실패해도 예외를 전파하지 않는다.
   *
   * @param document     대상 문서
   * @param errorMessage 사용자에게 보여줄 실패 사유
   */
  private void markFailed(UploadedDocument document, String errorMessage) {
    try {
      String trimmed = errorMessage.length() > MAX_ERROR_MESSAGE_LENGTH
          ? errorMessage.substring(0, MAX_ERROR_MESSAGE_LENGTH)
          : errorMessage;
      documentRepository.save(document.withStatus(DocumentStatus.FAILED).toBuilder()
          .errorMessage(trimmed)
          .build()).block();
    } catch (Exception e) {
      log.error("문서 실패 상태 저장 실패 - documentId: {}", document.getId(), e);
    }
  }

  /**
   * 페이지별 텍스트를 청킹하고, 출처 표시와 삭제에 필요한 메타데이터를 붙인 Document 목록을 만든다.
   * - 페이지 경계를 넘어 청킹되지 않도록 페이지마다 개별적으로 분할한다.
   * - chunk_index는 문서 전체를 기준으로 0부터 순서대로 매긴다.
   *
   * @param pages    페이지 번호와 텍스트 목록
   * @param document 청크가 속한 업로드 문서
   * @return user_id/document_id/filename/page/chunk_index 메타데이터가 태깅된 Document 목록
   */
  private List<Document> tagChunks(List<DocumentParser.PageText> pages, UploadedDocument document) {
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
      if (page.text() == null || page.text().isBlank()) {
        continue;
      }
      List<Document> pageChunks = splitter.split(List.of(new Document(page.text())));

      for (Document chunk : pageChunks) {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("user_id", document.getUserId());
        // 문서 삭제 시 해당 문서의 벡터만 골라 지우기 위한 연결 키
        metadata.put("document_id", document.getId());
        metadata.put("filename", document.getFilename() != null ? document.getFilename() : "unknown");
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
