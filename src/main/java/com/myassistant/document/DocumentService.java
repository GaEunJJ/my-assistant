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
import java.util.List;
import java.util.Map;

/**
 * 문서 비동기 인덱싱 서비스
 * - 파일 텍스트 추출 → 청킹 → 임베딩 → PgVector 저장
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
      // 1. 텍스트 추출
      String text = documentParser.parse(file);

      // 2. 청킹 (500 토큰 단위)
      TokenTextSplitter splitter = new TokenTextSplitter(CHUNK_SIZE, CHUNK_OVERLAP, 5, 10000, true);
      List<Document> chunks = splitter.split(List.of(new Document(text)));

      // 3. user_id 메타데이터 추가 (멀티테넌트 격리를 위한 필터 키)
      List<Document> taggedChunks = chunks.stream()
          .map(chunk -> new Document(
              chunk.getText(),
              Map.of(
                  "user_id", userId,
                  "filename", filename != null ? filename : "unknown"
              )
          ))
          .toList();

      // 4. 임베딩 생성 및 PgVector 저장
      vectorStore.add(taggedChunks);

      log.info("문서 인덱싱 완료 - userId: {}, file: {}, chunks: {}", userId, filename, taggedChunks.size());

    } catch (IOException e) {
      log.error("문서 텍스트 추출 실패 - userId: {}, file: {}", userId, filename, e);
    } catch (Exception e) {
      // 비동기 스레드에서 발생한 예외는 클라이언트로 전달되지 않으므로 반드시 로깅으로 확인
      log.error("문서 인덱싱 실패 - userId: {}, file: {}", userId, filename, e);
    }
  }
}
