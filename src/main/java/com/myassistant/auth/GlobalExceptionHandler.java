package com.myassistant.auth;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.bind.support.WebExchangeBindException;
import reactor.core.publisher.Mono;

import java.util.Map;
import java.util.stream.Collectors;

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
}
