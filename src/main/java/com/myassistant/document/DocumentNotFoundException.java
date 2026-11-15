package com.myassistant.document;

/**
 * 문서가 존재하지 않거나 다른 사용자의 문서일 때 발생하는 예외
 * - 다른 사용자 문서의 존재 여부가 노출되지 않도록 두 경우 모두 404로 처리한다
 */
public class DocumentNotFoundException extends RuntimeException {

  /**
   * 문서 ID로 예외를 생성한다.
   *
   * @param documentId 찾지 못한 문서 ID
   */
  public DocumentNotFoundException(Long documentId) {
    super("문서를 찾을 수 없습니다. (id: " + documentId + ")");
  }
}
