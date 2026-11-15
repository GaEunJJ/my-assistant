package com.myassistant.document.dto;

/**
 * 문서 업로드 응답 DTO
 *
 * @param message  안내 메시지
 * @param document 생성된 문서 정보 (status: PENDING)
 */
public record UploadResponse(String message, DocumentResponse document) {
}
