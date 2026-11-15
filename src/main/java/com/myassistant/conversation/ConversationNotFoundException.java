package com.myassistant.conversation;

/**
 * 대화가 존재하지 않거나 다른 사용자의 대화일 때 발생하는 예외
 * - 다른 사용자 대화의 존재 여부가 노출되지 않도록 두 경우 모두 404로 처리한다
 */
public class ConversationNotFoundException extends RuntimeException {

  /**
   * 대화 ID로 예외를 생성한다.
   *
   * @param conversationId 찾지 못한 대화 ID
   */
  public ConversationNotFoundException(Long conversationId) {
    super("대화를 찾을 수 없습니다. (id: " + conversationId + ")");
  }
}
