package com.myassistant.document;

/**
 * 업로드 파일이 최대 허용 크기를 넘었을 때 발생하는 예외 (413 Payload Too Large)
 */
public class FileTooLargeException extends RuntimeException {

  /**
   * 최대 허용 크기를 담은 안내 메시지로 예외를 생성한다.
   *
   * @param maxFileSizeLabel 최대 허용 크기 (예: "50MB")
   */
  public FileTooLargeException(String maxFileSizeLabel) {
    super("파일 크기가 너무 큽니다. " + maxFileSizeLabel + " 이하의 파일만 업로드할 수 있습니다.");
  }
}
