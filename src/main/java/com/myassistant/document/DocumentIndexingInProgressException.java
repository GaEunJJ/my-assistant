package com.myassistant.document;

/**
 * 인덱싱이 진행 중인 문서를 삭제하려 할 때 발생하는 예외 (409 Conflict)
 * - 삭제 직후 백그라운드 인덱싱이 벡터를 저장하면 고아 벡터가 남기 때문에 삭제를 막는다
 */
public class DocumentIndexingInProgressException extends RuntimeException {

  /**
   * 기본 안내 메시지로 예외를 생성한다.
   */
  public DocumentIndexingInProgressException() {
    super("인덱싱이 진행 중인 문서는 삭제할 수 없습니다. 완료 후 다시 시도해주세요.");
  }
}
