package com.myassistant.document;

import org.springframework.data.r2dbc.repository.Modifying;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.OffsetDateTime;

/**
 * 업로드 문서 R2DBC 레포지토리
 * - 멀티테넌트 격리를 위해 조회 메서드는 모두 userId 조건을 포함한다
 * - 인덱싱 상태 변경은 "현재 상태 조건"을 건 UPDATE로만 수행한다.
 *   (멈춘 문서 복구 작업이 FAILED로 바꾼 문서를, 뒤늦게 끝난 인덱싱 작업이 DONE으로 덮어쓰지 않도록)
 *   반환값은 변경된 행 수이며, 0이면 그 사이 상태가 바뀌었거나 문서가 삭제된 것이다.
 */
public interface UploadedDocumentRepository extends ReactiveCrudRepository<UploadedDocument, Long> {

  /**
   * 사용자의 문서 목록을 최신 업로드 순으로 조회한다.
   *
   * @param userId 사용자 ID
   * @return 해당 사용자의 문서 목록
   */
  Flux<UploadedDocument> findAllByUserIdOrderByCreatedAtDesc(String userId);

  /**
   * 사용자 본인의 문서를 ID로 조회한다.
   *
   * @param id     문서 ID
   * @param userId 사용자 ID
   * @return 조회된 문서, 없거나 다른 사용자의 문서면 empty
   */
  Mono<UploadedDocument> findByIdAndUserId(Long id, String userId);

  /**
   * PENDING 문서를 INDEXING으로 바꾼다.
   *
   * @param id 문서 ID
   * @return 변경된 행 수 (0이면 이미 실패 처리됐거나 삭제된 문서)
   */
  @Modifying
  @Query("UPDATE documents SET status = 'INDEXING', updated_at = NOW() WHERE id = :id AND status = 'PENDING'")
  Mono<Integer> markIndexing(Long id);

  /**
   * 인덱싱 진행 신호를 남긴다. (멈춘 문서로 오인되지 않도록 updated_at 갱신)
   *
   * @param id 문서 ID
   * @return 변경된 행 수 (0이면 그 사이 실패 처리됐거나 삭제된 문서)
   */
  @Modifying
  @Query("UPDATE documents SET updated_at = NOW() WHERE id = :id AND status = 'INDEXING'")
  Mono<Integer> touchIndexing(Long id);

  /**
   * INDEXING 문서를 DONE으로 바꾸고 청크 수를 기록한다.
   *
   * @param id         문서 ID
   * @param chunkCount 저장된 청크 수
   * @return 변경된 행 수 (0이면 그 사이 실패 처리됐거나 삭제된 문서)
   */
  @Modifying
  @Query("UPDATE documents SET status = 'DONE', chunk_count = :chunkCount, updated_at = NOW() "
      + "WHERE id = :id AND status = 'INDEXING'")
  Mono<Integer> markDone(Long id, int chunkCount);

  /**
   * 진행 중(PENDING/INDEXING)인 문서를 FAILED로 바꾸고 사유를 기록한다.
   *
   * @param id           문서 ID
   * @param errorMessage 사용자에게 보여줄 실패 사유
   * @return 변경된 행 수
   */
  @Modifying
  @Query("UPDATE documents SET status = 'FAILED', error_message = :errorMessage, updated_at = NOW() "
      + "WHERE id = :id AND status IN ('PENDING', 'INDEXING')")
  Mono<Integer> markFailed(Long id, String errorMessage);

  /**
   * 기준 시각 이후로 갱신이 없는 진행 중 문서를 모두 FAILED로 바꾼다. (서버 재시작 등으로 멈춘 문서 복구)
   * - 모든 사용자의 문서가 대상인 시스템 작업이므로 user_id 조건이 없다.
   *
   * @param threshold    이 시각보다 updated_at이 오래된 문서를 멈춘 것으로 판단
   * @param errorMessage 사용자에게 보여줄 실패 사유
   * @return 실패 처리된 문서 수
   */
  @Modifying
  @Query("UPDATE documents SET status = 'FAILED', error_message = :errorMessage, updated_at = NOW() "
      + "WHERE status IN ('PENDING', 'INDEXING') AND updated_at < :threshold")
  Mono<Integer> failStale(OffsetDateTime threshold, String errorMessage);
}
