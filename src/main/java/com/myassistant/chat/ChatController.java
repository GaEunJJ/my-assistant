package com.myassistant.chat;

import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

/**
 * AI 채팅 SSE 스트리밍 API 컨트롤러
 * - GET /api/chat/stream?message={질문} 으로 출처(sources) + 답변 토큰(token) 이벤트 스트림 제공
 */
@RestController
@RequestMapping("/api/chat")
@RequiredArgsConstructor
public class ChatController {

  private final ChatService chatService;

  // 질문 최대 길이 (GET 쿼리 파라미터 길이 제한과 프롬프트 비용을 고려)
  private static final int MAX_MESSAGE_LENGTH = 2000;

  /**
   * GET /api/chat/stream
   * - SSE(Server-Sent Events) 방식으로 conversation(대화 ID) → sources(출처 목록) → token(답변 토큰) 이벤트를 스트리밍
   * - conversationId를 넘기면 이전 대화 맥락을 이어가고, 생략하면 새 대화를 만든다
   * - 첫 토큰을 빠르게 전달해 체감 응답속도 개선
   *
   * @param message        사용자 질문
   * @param conversationId 이어갈 대화 ID (생략 시 새 대화)
   * @param userDetails    인증된 사용자 정보
   * @return SSE 이벤트 스트림
   * @throws IllegalArgumentException 질문이 비어있거나 너무 긴 경우 (400)
   * @throws com.myassistant.conversation.ConversationNotFoundException 대화가 없거나 다른 사용자의 대화인 경우 (404)
   */
  @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
  public Flux<ServerSentEvent<String>> stream(
      @RequestParam String message,
      @RequestParam(required = false) Long conversationId,
      @AuthenticationPrincipal UserDetails userDetails
  ) {
    if (message.isBlank()) {
      throw new IllegalArgumentException("질문을 입력해주세요.");
    }
    if (message.length() > MAX_MESSAGE_LENGTH) {
      throw new IllegalArgumentException("질문은 " + MAX_MESSAGE_LENGTH + "자 이하로 입력해주세요.");
    }
    return chatService.streamAnswer(message.strip(), conversationId, userDetails.getUsername());
  }
}
