package com.myassistant.document;

/**
 * 업로드 문서의 인덱싱 상태
 * - PENDING: 업로드 접수, 인덱싱 대기 중
 * - INDEXING: 텍스트 추출/청킹/임베딩 진행 중
 * - DONE: 인덱싱 완료, 채팅 검색 대상
 * - FAILED: 인덱싱 실패 (error_message에 사유 기록)
 */
public enum DocumentStatus {
  PENDING,
  INDEXING,
  DONE,
  FAILED;

  /**
   * 인덱싱이 아직 끝나지 않은 상태인지 확인한다.
   *
   * @return PENDING 또는 INDEXING이면 true
   */
  public boolean isInProgress() {
    return this == PENDING || this == INDEXING;
  }
}
