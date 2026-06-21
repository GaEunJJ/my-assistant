package com.myassistant.document;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

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
   */
  @PostMapping("/upload")
  public ResponseEntity<Map<String, String>> upload(
      @RequestParam("file") MultipartFile file,
      @AuthenticationPrincipal UserDetails userDetails
  ) {
    if (file.isEmpty()) {
      throw new IllegalArgumentException("파일이 비어있습니다.");
    }

    String userId = userDetails.getUsername();
    documentService.indexAsync(file, userId);

    return ResponseEntity.status(HttpStatus.ACCEPTED)
        .body(Map.of("message", "인덱싱을 시작했어요. 잠시 후 채팅이 가능합니다."));
  }
}
