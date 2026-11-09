package com.myassistant.chat.dto;

/**
 * 답변 생성에 사용된 문서 출처 정보.
 *
 * @param filename   출처 파일명
 * @param page       PDF 페이지 번호(1부터 시작), 페이지 개념이 없는 형식(DOCX, TXT 등)이면 null
 * @param chunkIndex 문서 내 청크 순번(0부터 시작)
 */
public record SourceInfo(String filename, Integer page, Integer chunkIndex) {
}
