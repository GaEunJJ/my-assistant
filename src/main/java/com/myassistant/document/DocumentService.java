package com.myassistant.document;

import com.myassistant.document.dto.DocumentResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.OffsetDateTime;

/**
 * 업로드 문서 관리 서비스
 * - 업로드 접수(PENDING 저장 후 비동기 인덱싱 시작), 목록 조회, 삭제(벡터 포함)를 담당한다
 * - 모든 조회/삭제는 userId 조건으로 본인 문서만 다룬다 (멀티테넌트 격리)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentService {

  private final UploadedDocumentRepository documentRepository;
  private final DocumentIndexingService indexingService;
  private final VectorStore vectorStore;

  /**
   * 업로드된 문서를 PENDING 상태로 저장하고 백그라운드 인덱싱을 시작한다.
   *
   * @param fileContent 파일 전체 바이트 배열
   * @param filename    원본 파일명
   * @param userId      업로드한 사용자 ID
   * @return PENDING 상태로 저장된 문서
   */
  public Mono<UploadedDocument> upload(byte[] fileContent, String filename, String userId) {
    OffsetDateTime now = OffsetDateTime.now();
    UploadedDocument pending = UploadedDocument.builder()
        .userId(userId)
        .filename(filename != null ? filename : "unknown")
        .status(DocumentStatus.PENDING)
        .createdAt(now)
        .updatedAt(now)
        .build();

    return documentRepository.save(pending)
        // 저장된 문서 ID가 있어야 벡터 메타데이터에 document_id를 남길 수 있으므로 저장 후 인덱싱 시작
        // indexAsync는 @Async라 즉시 반환되며 이벤트 루프를 막지 않는다
        .doOnNext(saved -> indexingService.indexAsync(saved, fileContent));
  }

  /**
   * 사용자의 문서 목록을 최신 업로드 순으로 조회한다.
   *
   * @param userId 사용자 ID
   * @return 문서 응답 DTO 스트림
   */
  public Flux<DocumentResponse> findAll(String userId) {
    return documentRepository.findAllByUserIdOrderByCreatedAtDesc(userId)
        .map(DocumentResponse::from);
  }

  /**
   * 사용자 본인의 문서와 해당 문서의 벡터 청크를 모두 삭제한다.
   *
   * @param documentId 삭제할 문서 ID
   * @param userId     사용자 ID
   * @return 삭제 완료 신호
   * @throws DocumentNotFoundException 문서가 없거나 다른 사용자의 문서인 경우
   * @throws DocumentIndexingInProgressException 인덱싱이 진행 중인 경우 (삭제 후 벡터가 뒤늦게 저장되는 것을 방지)
   */
  public Mono<Void> delete(Long documentId, String userId) {
    return documentRepository.findByIdAndUserId(documentId, userId)
        .switchIfEmpty(Mono.error(new DocumentNotFoundException(documentId)))
        .flatMap(document -> {
          if (document.getStatus().isInProgress()) {
            return Mono.error(new DocumentIndexingInProgressException());
          }
          // 벡터 먼저 삭제: 벡터 삭제가 실패하면 문서 행이 남아 사용자가 다시 삭제를 시도할 수 있다
          return deleteVectors(documentId, userId)
              .then(documentRepository.delete(document));
        })
        .doOnSuccess(unused -> log.info("문서 삭제 완료 - userId: {}, documentId: {}", userId, documentId));
  }

  /**
   * 문서에 속한 벡터 청크를 PgVector에서 삭제한다.
   * - user_id 조건도 함께 걸어 다른 사용자의 벡터가 지워지는 일이 없도록 한다.
   * - PgVectorStore는 JDBC(블로킹)이므로 boundedElastic 스케줄러에서 실행한다.
   *
   * @param documentId 문서 ID
   * @param userId     사용자 ID
   * @return 삭제 완료 신호
   */
  private Mono<Void> deleteVectors(Long documentId, String userId) {
    FilterExpressionBuilder filterBuilder = new FilterExpressionBuilder();
    return Mono.fromRunnable(() -> vectorStore.delete(
            filterBuilder.and(
                filterBuilder.eq("user_id", userId),
                filterBuilder.eq("document_id", documentId)
            ).build()))
        .subscribeOn(Schedulers.boundedElastic())
        .then();
  }
}
