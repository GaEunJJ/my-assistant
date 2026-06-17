package com.myassistant.tenant;

/**
 * 현재 요청의 사용자 ID를 스레드 로컬로 관리하는 멀티테넌트 컨텍스트
 */
public class TenantContext {

  private static final ThreadLocal<String> currentUserId = new ThreadLocal<>();

  public static void setUserId(String userId) {
    currentUserId.set(userId);
  }

  public static String getUserId() {
    return currentUserId.get();
  }

  public static void clear() {
    currentUserId.remove();
  }
}
