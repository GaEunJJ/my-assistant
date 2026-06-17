package com.myassistant.chat;

import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

/**
 * AI 채팅 SSE 스트리밍 API 컨트롤러
 * - GET /api/chat/stream?message={질문} 으로 토큰 단위 실시간 응답 제공
 */
@RestController
@RequestMapping("/api/chat")
@RequiredArgsConstructor
public class ChatController {

  private final ChatService chatService;

  /**
   * GET /api/chat/stream
   * - SSE(Server-Sent Events) 방식으로 토큰 단위 스트리밍 응답
   * - 첫 토큰을 빠르게 전달해 체감 응답속도 개선
   */
  @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
  public Flux<String> stream(
      @RequestParam String message,
      @AuthenticationPrincipal UserDetails userDetails
  ) {
    String userId = userDetails.getUsername();
    return chatService.streamAnswer(message, userId);
  }
}
