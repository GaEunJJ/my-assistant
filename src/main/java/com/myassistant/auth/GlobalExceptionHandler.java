package com.myassistant.auth;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.bind.support.WebExchangeBindException;
import reactor.core.publisher.Mono;

import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

  @ExceptionHandler(IllegalArgumentException.class)
  public Mono<ResponseEntity<Map<String, String>>> handleIllegalArgument(IllegalArgumentException e) {
    return Mono.just(ResponseEntity.status(HttpStatus.BAD_REQUEST)
        .body(Map.of("message", e.getMessage())));
  }

  // @Valid 실패 시 필드별 에러 메시지 반환
  @ExceptionHandler(WebExchangeBindException.class)
  public Mono<ResponseEntity<Map<String, String>>> handleValidation(WebExchangeBindException e) {
    String message = e.getBindingResult().getFieldErrors().stream()
        .map(error -> error.getField() + ": " + error.getDefaultMessage())
        .collect(Collectors.joining(", "));
    return Mono.just(ResponseEntity.status(HttpStatus.BAD_REQUEST)
        .body(Map.of("message", message)));
  }

  // 위에서 처리하지 못한 모든 예외에 대한 최종 안전망 — 내부 오류 상세는 로그로만 남기고 클라이언트에는 노출하지 않음
  @ExceptionHandler(Exception.class)
  public Mono<ResponseEntity<Map<String, String>>> handleUnexpected(Exception e) {
    log.error("처리되지 않은 예외 발생", e);
    return Mono.just(ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
        .body(Map.of("message", "서버 내부 오류가 발생했습니다.")));
  }
}
