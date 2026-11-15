package com.myassistant.document;

import com.myassistant.document.dto.DocumentResponse;
import com.myassistant.support.IntegrationTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.nio.charset.StandardCharsets;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * 멈춘 문서 복구 통합 테스트
 * - 서버 재시작 등으로 PENDING/INDEXING에 멈춘 문서가 FAILED로 바뀌어 사용자가 삭제·재업로드할 수 있는지 검증한다
 * - 정상 진행 중이거나 이미 끝난 문서는 건드리지 않는지, 실패 처리된 문서를 인덱싱 작업이 덮어쓰지 않는지 검증한다
 */
@DisplayName("멈춘 문서 복구")
class StaleDocumentRecoveryIntegrationTest extends IntegrationTestSupport {

  @Autowired
  private StaleDocumentRecoveryService recoveryService;

  @Autowired
  private DocumentIndexingService indexingService;

  /**
   * 오래 진행 신호가 없는 PENDING/INDEXING 문서는 FAILED가 되고, 사용자가 사유를 보고 삭제할 수 있다.
   */
  @Test
  @DisplayName("오래 멈춘 PENDING·INDEXING 문서는 FAILED로 바뀌고 삭제할 수 있다")
  void staleDocumentsAreFailedAndDeletable() {
    String username = uniqueUsername("user");
    String token = registerAndGetToken(username);
    Long stuckIndexing = insertDocument(username, DocumentStatus.INDEXING, Duration.ofMinutes(20));
    Long stuckPending = insertDocument(username, DocumentStatus.PENDING, Duration.ofMinutes(20));

    recoveryService.recoverStaleDocuments();

    assertThat(listDocuments(token))
        .filteredOn(document -> document.id().equals(stuckIndexing) || document.id().equals(stuckPending))
        .hasSize(2)
        .allSatisfy(document -> {
          assertThat(document.status()).isEqualTo(DocumentStatus.FAILED);
          assertThat(document.errorMessage()).isEqualTo(StaleDocumentRecoveryService.STALE_MESSAGE);
        });
    // 수정 전에는 인덱싱 중 문서라 409로 삭제가 막혔던 문서
    deleteDocument(token, stuckIndexing).expectStatus().isNoContent();
  }

  /**
   * 최근에 진행 신호가 있었던 문서(정상 처리 중)와 이미 끝난 문서는 복구 대상이 아니다.
   */
  @Test
  @DisplayName("최근 진행 중인 문서와 이미 끝난 문서는 건드리지 않는다")
  void activeAndFinishedDocumentsAreNotTouched() {
    String username = uniqueUsername("user");
    String token = registerAndGetToken(username);
    Long activeIndexing = insertDocument(username, DocumentStatus.INDEXING, Duration.ofMinutes(1));
    Long oldDone = insertDocument(username, DocumentStatus.DONE, Duration.ofDays(3));

    recoveryService.recoverStaleDocuments();

    assertThat(listDocuments(token))
        .filteredOn(document -> document.id().equals(activeIndexing))
        .singleElement().extracting(DocumentResponse::status).isEqualTo(DocumentStatus.INDEXING);
    assertThat(listDocuments(token))
        .filteredOn(document -> document.id().equals(oldDone))
        .singleElement().extracting(DocumentResponse::status).isEqualTo(DocumentStatus.DONE);
  }

  /**
   * 인덱싱 작업이 시작되기 전에 문서가 실패 처리되었다면, 작업은 시작하지 않고 벡터도 저장하지 않는다.
   * (작업 대기열에 오래 머문 문서가 복구 작업으로 FAILED가 된 뒤 뒤늦게 실행되는 상황)
   */
  @Test
  @DisplayName("실패 처리된 문서는 뒤늦게 실행된 인덱싱 작업이 DONE으로 덮어쓰지 않는다")
  void lateIndexingDoesNotOverwriteFailedDocument() {
    String username = uniqueUsername("user");
    String token = registerAndGetToken(username);
    Long documentId = insertDocument(username, DocumentStatus.FAILED, Duration.ZERO);

    UploadedDocument document = UploadedDocument.builder()
        .id(documentId)
        .userId(username)
        .filename("late.txt")
        .status(DocumentStatus.PENDING)
        .build();
    indexingService.indexAsync(document, "뒤늦게 실행된 인덱싱 작업 내용".getBytes(StandardCharsets.UTF_8));

    // 비동기 작업이 끝날 시간 동안 상태가 계속 FAILED이고 벡터가 생기지 않는지 확인
    await().during(Duration.ofSeconds(2)).atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
      assertThat(listDocuments(token))
          .filteredOn(item -> item.id().equals(documentId))
          .singleElement().extracting(DocumentResponse::status).isEqualTo(DocumentStatus.FAILED);
      assertThat(countVectorsOfDocument(documentId)).isZero();
    });
  }

  /**
   * 테스트용 문서 행을 직접 넣는다. (서버 재시작으로 멈춘 상태를 재현하기 위해 updated_at을 과거로 설정)
   *
   * @param userId 소유자 ID
   * @param status 문서 상태
   * @param age    현재로부터 얼마나 오래 전에 마지막으로 갱신되었는지
   * @return 생성된 문서 ID
   */
  private Long insertDocument(String userId, DocumentStatus status, Duration age) {
    return jdbcTemplate.queryForObject(
        "INSERT INTO documents (user_id, filename, status, created_at, updated_at) "
            + "VALUES (?, ?, ?, NOW() - make_interval(secs => ?), NOW() - make_interval(secs => ?)) RETURNING id",
        Long.class, userId, "stuck-" + status + ".txt", status.name(), age.toSeconds(), age.toSeconds());
  }
}
