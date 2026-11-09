package com.myassistant.document;

import lombok.RequiredArgsConstructor;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.Map;

/**
 * 문서 업로드 API 컨트롤러
 * - 파일 수신 후 즉시 202 반환, 인덱싱은 백그라운드 비동기 처리
 */
@RestController
@RequestMapping("/api/documents")
@RequiredArgsConstructor
public class DocumentController {

  private final DocumentService documentService;

  /**
   * POST /api/documents/upload
   * - 파일 업로드 즉시 202 Accepted 반환
   * - 인덱싱은 백그라운드에서 비동기 처리
   *
   * @param file        업로드된 파일 (WebFlux 멀티파트 Part)
   * @param userDetails 인증된 사용자 정보
   * @return 202 Accepted 응답
   * @throws IllegalArgumentException 파일이 비어있는 경우
   */
  @PostMapping("/upload")
  public Mono<ResponseEntity<Map<String, String>>> upload(
      @RequestPart("file") FilePart file,
      @AuthenticationPrincipal UserDetails userDetails
  ) {
    String userId = userDetails.getUsername();
    String filename = file.filename();

    // FilePart의 콘텐츠는 요청 처리가 끝나면 더 이상 읽을 수 없으므로,
    // 백그라운드 인덱싱에 넘기기 전에 바이트 배열로 전부 읽어둔다
    return DataBufferUtils.join(file.content())
        .map(this::toByteArray)
        .flatMap(bytes -> {
          if (bytes.length == 0) {
            return Mono.error(new IllegalArgumentException("파일이 비어있습니다."));
          }
          documentService.indexAsync(bytes, filename, userId);
          return Mono.just(ResponseEntity.status(HttpStatus.ACCEPTED)
              .body(Map.of("message", "인덱싱을 시작했어요. 잠시 후 채팅이 가능합니다.")));
        });
  }

  /**
   * DataBuffer를 바이트 배열로 변환하고 버퍼를 해제한다.
   *
   * @param buffer 병합된 파일 콘텐츠 버퍼
   * @return 파일 전체 바이트 배열
   */
  private byte[] toByteArray(DataBuffer buffer) {
    byte[] bytes = new byte[buffer.readableByteCount()];
    buffer.read(bytes);
    DataBufferUtils.release(buffer);
    return bytes;
  }
}
