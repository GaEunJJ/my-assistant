package com.myassistant.oauth2;

import java.util.Map;

/**
 * 카카오·네이버·구글에서 받은 사용자 정보를 공통 형태로 정규화하는 값 객체.
 *
 * @param providerId 소셜 플랫폼 내 고유 사용자 ID
 * @param provider   소셜 플랫폼 식별자 (kakao / naver / google)
 * @param email      사용자 이메일 (동의하지 않으면 null일 수 있음)
 * @param name       사용자 표시 이름
 */
public record OAuthAttributes(
    String providerId,
    String provider,
    String email,
    String name
) {

  /**
   * registrationId에 따라 플랫폼별 파싱 메서드를 선택하여 OAuthAttributes를 생성한다.
   *
   * @param registrationId        Spring Security 클라이언트 등록 ID (kakao / naver / google)
   * @param userNameAttributeName 사용자 식별자 키 (provider 설정의 user-name-attribute)
   * @param attributes            소셜 플랫폼 userinfo 응답 Map
   * @return 정규화된 OAuthAttributes
   */
  public static OAuthAttributes of(
      String registrationId,
      String userNameAttributeName,
      Map<String, Object> attributes
  ) {
    return switch (registrationId) {
      case "kakao" -> ofKakao(attributes);
      case "naver" -> ofNaver(attributes);
      default -> ofGoogle(userNameAttributeName, attributes);
    };
  }

  /**
   * 구글 userinfo 응답을 파싱한다. (sub 필드가 고유 ID)
   *
   * @param userNameAttributeName 구글 설정의 user-name-attribute (기본값: sub)
   * @param attributes            구글 userinfo 응답 Map
   * @return OAuthAttributes
   */
  private static OAuthAttributes ofGoogle(
      String userNameAttributeName,
      Map<String, Object> attributes
  ) {
    return new OAuthAttributes(
        String.valueOf(attributes.get(userNameAttributeName)),
        "google",
        (String) attributes.get("email"),
        (String) attributes.get("name")
    );
  }

  /**
   * 카카오 userinfo 응답을 파싱한다. (kakao_account > profile 구조)
   *
   * @param attributes 카카오 /v2/user/me 응답 Map
   * @return OAuthAttributes
   */
  @SuppressWarnings("unchecked")
  private static OAuthAttributes ofKakao(Map<String, Object> attributes) {
    Map<String, Object> kakaoAccount = (Map<String, Object>) attributes.get("kakao_account");
    Map<String, Object> profile = (Map<String, Object>) kakaoAccount.get("profile");

    // 이메일 동의 여부에 따라 null일 수 있음
    String email = (String) kakaoAccount.get("email");
    return new OAuthAttributes(
        String.valueOf(attributes.get("id")),
        "kakao",
        email,
        (String) profile.get("nickname")
    );
  }

  /**
   * 네이버 userinfo 응답을 파싱한다. (response 키 하위에 실제 정보 위치)
   *
   * @param attributes 네이버 /v1/nid/me 응답 Map
   * @return OAuthAttributes
   */
  @SuppressWarnings("unchecked")
  private static OAuthAttributes ofNaver(Map<String, Object> attributes) {
    Map<String, Object> response = (Map<String, Object>) attributes.get("response");
    return new OAuthAttributes(
        (String) response.get("id"),
        "naver",
        (String) response.get("email"),
        (String) response.get("name")
    );
  }
}
