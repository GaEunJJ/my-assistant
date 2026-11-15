package com.myassistant.auth;

import com.myassistant.conversation.ConversationNotFoundException;
import com.myassistant.document.DocumentIndexingInProgressException;
import com.myassistant.document.DocumentNotFoundException;
import com.myassistant.document.DocumentUploadValidator;
import com.myassistant.document.FileTooLargeException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.buffer.DataBufferLimitException;
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
@RequiredArgsConstructor
public class GlobalExceptionHandler {

  private final DocumentUploadValidator uploadValidator;

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

  /**
   * 문서가 없거나 다른 사용자의 문서일 때 404를 반환한다.
   *
   * @param e 문서 미존재 예외
   * @return 404 Not Found 응답
   */
  @ExceptionHandler(DocumentNotFoundException.class)
  public Mono<ResponseEntity<Map<String, String>>> handleDocumentNotFound(DocumentNotFoundException e) {
    return Mono.just(ResponseEntity.status(HttpStatus.NOT_FOUND)
        .body(Map.of("message", e.getMessage())));
  }

  /**
   * 대화가 없거나 다른 사용자의 대화일 때 404를 반환한다.
   *
   * @param e 대화 미존재 예외
   * @return 404 Not Found 응답
   */
  @ExceptionHandler(ConversationNotFoundException.class)
  public Mono<ResponseEntity<Map<String, String>>> handleConversationNotFound(ConversationNotFoundException e) {
    return Mono.just(ResponseEntity.status(HttpStatus.NOT_FOUND)
        .body(Map.of("message", e.getMessage())));
  }

  /**
   * 인덱싱 중인 문서를 삭제하려 할 때 409를 반환한다.
   *
   * @param e 인덱싱 진행 중 예외
   * @return 409 Conflict 응답
   */
  @ExceptionHandler(DocumentIndexingInProgressException.class)
  public Mono<ResponseEntity<Map<String, String>>> handleIndexingInProgress(DocumentIndexingInProgressException e) {
    return Mono.just(ResponseEntity.status(HttpStatus.CONFLICT)
        .body(Map.of("message", e.getMessage())));
  }

  /**
   * 업로드 파일이 최대 허용 크기를 넘었을 때 413을 반환한다.
   *
   * @param e 파일 크기 초과 예외
   * @return 413 Payload Too Large 응답
   */
  @ExceptionHandler(FileTooLargeException.class)
  public Mono<ResponseEntity<Map<String, String>>> handleFileTooLarge(FileTooLargeException e) {
    return Mono.just(ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
        .body(Map.of("message", e.getMessage())));
  }

  /**
   * 멀티파트 코덱 단계에서 파트 크기 한도(spring.webflux.multipart.max-disk-usage-per-part)를 넘었을 때 413을 반환한다.
   * - 컨트롤러에 도달하기 전에 발생하므로 FileTooLargeException과 별도로 처리한다.
   *
   * @param e 데이터 버퍼 한도 초과 예외
   * @return 413 Payload Too Large 응답
   */
  @ExceptionHandler(DataBufferLimitException.class)
  public Mono<ResponseEntity<Map<String, String>>> handleDataBufferLimit(DataBufferLimitException e) {
    return handleFileTooLarge(new FileTooLargeException(uploadValidator.maxFileSizeLabel()));
  }

  // 위에서 처리하지 못한 모든 예외에 대한 최종 안전망 — 내부 오류 상세는 로그로만 남기고 클라이언트에는 노출하지 않음
  @ExceptionHandler(Exception.class)
  public Mono<ResponseEntity<Map<String, String>>> handleUnexpected(Exception e) {
    log.error("처리되지 않은 예외 발생", e);
    return Mono.just(ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
        .body(Map.of("message", "서버 내부 오류가 발생했습니다.")));
  }
}
