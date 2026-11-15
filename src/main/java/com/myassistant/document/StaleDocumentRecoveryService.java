package com.myassistant.document;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.OffsetDateTime;

/**
 * 멈춘 문서 복구 서비스
 * - 인덱싱은 서버 메모리의 @Async 작업이라, 처리 중 서버가 재시작·종료되면 문서가 PENDING/INDEXING에 영원히 남는다.
 *   (목록에 계속 "인덱싱 중"으로 보이고, 인덱싱 중 문서는 삭제가 막혀 사용자가 정리할 수 없음)
 * - 주기적으로 일정 시간(stale-timeout) 이상 진행 신호가 없는 문서를 FAILED로 바꿔, 사용자가 삭제·재업로드할 수 있게 한다.
 * - 정상 진행 중인 문서는 DocumentIndexingService가 청크 묶음마다 updated_at을 갱신하므로 대상이 되지 않는다.
 *   시간 기준으로만 판단하므로 서버를 여러 대로 늘려도 다른 서버가 처리 중인 문서를 잘못 실패 처리하지 않는다.
 */
@Slf4j
@Service
public class StaleDocumentRecoveryService {

  static final String STALE_MESSAGE = "인덱싱이 중단되었습니다. (서버 재시작 등) 문서를 삭제한 뒤 다시 업로드해주세요.";

  private final UploadedDocumentRepository documentRepository;
  private final Duration staleTimeout;

  /**
   * 레포지토리와 멈춤 판단 기준 시간을 주입받는다.
   *
   * @param documentRepository 문서 레포지토리
   * @param staleTimeout       이 시간 이상 진행 신호가 없으면 멈춘 문서로 판단 (app.indexing.stale-timeout, 기본 10분)
   */
  public StaleDocumentRecoveryService(
      UploadedDocumentRepository documentRepository,
      @Value("${app.indexing.stale-timeout:10m}") Duration staleTimeout
  ) {
    this.documentRepository = documentRepository;
    this.staleTimeout = staleTimeout;
  }

  /**
   * 서버 시작 직후 1회, 이후 주기적으로(app.indexing.recovery-interval, 기본 1분) 멈춘 문서를 실패 처리한다.
   * - 스케줄러 스레드(이벤트 루프 아님)에서 실행되므로 block()을 사용한다.
   */
  @Scheduled(initialDelayString = "PT0S", fixedDelayString = "${app.indexing.recovery-interval:PT1M}")
  public void recoverScheduled() {
    try {
      recoverStaleDocuments();
    } catch (Exception e) {
      // 다음 주기에 다시 시도하면 되므로 스케줄러가 멈추지 않도록 예외를 삼킨다
      log.error("멈춘 문서 복구 실패", e);
    }
  }

  /**
   * 기준 시간 이상 진행 신호가 없는 PENDING/INDEXING 문서를 FAILED로 바꾼다.
   *
   * @return 실패 처리된 문서 수
   */
  public int recoverStaleDocuments() {
    OffsetDateTime threshold = OffsetDateTime.now().minus(staleTimeout);
    Integer recovered = documentRepository.failStale(threshold, STALE_MESSAGE).block();
    int count = recovered == null ? 0 : recovered;
    if (count > 0) {
      log.warn("멈춘 문서 {}건을 실패 처리했습니다. (기준: {} 이상 진행 없음)", count, staleTimeout);
    }
    return count;
  }
}
