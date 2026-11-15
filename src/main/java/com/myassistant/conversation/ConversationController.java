package com.myassistant.conversation;

import com.myassistant.conversation.dto.ChatMessageResponse;
import com.myassistant.conversation.dto.ConversationResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * 대화 이력 API 컨트롤러
 * - 대화 목록, 대화별 메시지 조회, 대화 삭제
 * - 새 대화 생성과 메시지 저장은 채팅 스트리밍(GET /api/chat/stream) 시 자동으로 이루어진다
 */
@RestController
@RequestMapping("/api/conversations")
@RequiredArgsConstructor
public class ConversationController {

  private final ConversationService conversationService;

  /**
   * GET /api/conversations
   * - 내 대화 목록을 최근 활동 순으로 반환한다.
   *
   * @param userDetails 인증된 사용자 정보
   * @return 대화 목록
   */
  @GetMapping
  public Flux<ConversationResponse> list(@AuthenticationPrincipal UserDetails userDetails) {
    return conversationService.findAll(userDetails.getUsername());
  }

  /**
   * GET /api/conversations/{id}/messages
   * - 대화의 전체 메시지를 시간순으로 반환한다. (새로고침 후 화면 복원용)
   *
   * @param id          대화 ID
   * @param userDetails 인증된 사용자 정보
   * @return 메시지 목록
   * @throws ConversationNotFoundException 대화가 없거나 다른 사용자의 대화인 경우 (404)
   */
  @GetMapping("/{id}/messages")
  public Flux<ChatMessageResponse> messages(
      @PathVariable Long id,
      @AuthenticationPrincipal UserDetails userDetails
  ) {
    return conversationService.findMessages(id, userDetails.getUsername());
  }

  /**
   * DELETE /api/conversations/{id}
   * - 대화와 그 메시지를 모두 삭제한다.
   *
   * @param id          대화 ID
   * @param userDetails 인증된 사용자 정보
   * @return 204 No Content
   * @throws ConversationNotFoundException 대화가 없거나 다른 사용자의 대화인 경우 (404)
   */
  @DeleteMapping("/{id}")
  public Mono<ResponseEntity<Void>> delete(
      @PathVariable Long id,
      @AuthenticationPrincipal UserDetails userDetails
  ) {
    return conversationService.delete(id, userDetails.getUsername())
        .then(Mono.just(ResponseEntity.noContent().<Void>build()));
  }
}
