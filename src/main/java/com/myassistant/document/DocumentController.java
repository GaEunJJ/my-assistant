package com.myassistant.document;

import com.myassistant.document.dto.DocumentResponse;
import com.myassistant.document.dto.UploadResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferLimitException;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * 문서 관리 API 컨트롤러
 * - 업로드: 파일 수신 후 PENDING 문서를 만들고 즉시 202 반환, 인덱싱은 백그라운드 비동기 처리
 * - 목록 조회: 인덱싱 상태(PENDING/INDEXING/DONE/FAILED) 포함
 * - 삭제: 문서와 벡터 청크를 함께 삭제
 */
@RestController
@RequestMapping("/api/documents")
@RequiredArgsConstructor
public class DocumentController {

  private final DocumentService documentService;
  private final DocumentUploadValidator uploadValidator;

  /**
   * POST /api/documents/upload
   * - 파일 업로드 즉시 202 Accepted와 생성된 문서 정보(status: PENDING) 반환
   * - 인덱싱은 백그라운드에서 비동기 처리되며, 진행 상태는 목록 조회 API로 확인한다
   *
   * @param file        업로드된 파일 (WebFlux 멀티파트 Part)
   * @param userDetails 인증된 사용자 정보
   * @return 202 Accepted 응답과 생성된 문서 정보
   * @throws IllegalArgumentException 파일이 비어있거나 지원하지 않는 형식인 경우 (400)
   * @throws FileTooLargeException    파일이 최대 허용 크기를 넘는 경우 (413)
   */
  @PostMapping("/upload")
  public Mono<ResponseEntity<UploadResponse>> upload(
      @RequestPart("file") FilePart file,
      @AuthenticationPrincipal UserDetails userDetails
  ) {
    String userId = userDetails.getUsername();
    String filename = file.filename();

    // 본문을 읽기 전에 확장자부터 검사 (지원하지 않는 파일은 바로 400)
    uploadValidator.validateFilename(filename);

    // FilePart의 콘텐츠는 요청 처리가 끝나면 더 이상 읽을 수 없으므로,
    // 백그라운드 인덱싱에 넘기기 전에 바이트 배열로 전부 읽어둔다
    // maxBytes를 넘으면 메모리에 다 올리기 전에 DataBufferLimitException으로 중단된다
    return DataBufferUtils.join(file.content(), uploadValidator.maxBytes())
        .onErrorMap(DataBufferLimitException.class,
            e -> new FileTooLargeException(uploadValidator.maxFileSizeLabel()))
        .map(this::toByteArray)
        .flatMap(bytes -> {
          if (bytes.length == 0) {
            return Mono.error(new IllegalArgumentException("파일이 비어있습니다."));
          }
          return documentService.upload(bytes, filename, userId);
        })
        .map(saved -> ResponseEntity.status(HttpStatus.ACCEPTED)
            .body(new UploadResponse(
                "인덱싱을 시작했어요. 완료되면 채팅에서 사용할 수 있습니다.",
                DocumentResponse.from(saved))));
  }

  /**
   * GET /api/documents
   * - 내 문서 목록을 최신 업로드 순으로 반환한다.
   *
   * @param userDetails 인증된 사용자 정보
   * @return 문서 목록
   */
  @GetMapping
  public Flux<DocumentResponse> list(@AuthenticationPrincipal UserDetails userDetails) {
    return documentService.findAll(userDetails.getUsername());
  }

  /**
   * DELETE /api/documents/{id}
   * - 내 문서와 해당 문서의 벡터 청크를 삭제한다.
   *
   * @param id          삭제할 문서 ID
   * @param userDetails 인증된 사용자 정보
   * @return 204 No Content
   * @throws DocumentNotFoundException 문서가 없거나 다른 사용자의 문서인 경우 (404)
   * @throws DocumentIndexingInProgressException 인덱싱이 진행 중인 경우 (409)
   */
  @DeleteMapping("/{id}")
  public Mono<ResponseEntity<Void>> delete(
      @PathVariable Long id,
      @AuthenticationPrincipal UserDetails userDetails
  ) {
    return documentService.delete(id, userDetails.getUsername())
        .then(Mono.just(ResponseEntity.noContent().<Void>build()));
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
