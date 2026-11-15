package com.myassistant.document;

import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * 업로드 문서 R2DBC 레포지토리
 * - 멀티테넌트 격리를 위해 조회 메서드는 모두 userId 조건을 포함한다
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
}
