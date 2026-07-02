package com.myassistant.oauth2;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.core.user.OAuth2User;

import java.util.Collection;
import java.util.Map;

/**
 * 소셜 플랫폼의 OAuth2User를 래핑하여 우리 DB의 username을 getName()으로 반환하는 클래스.
 * SuccessHandler에서 authentication.getName()으로 DB username을 바로 꺼낼 수 있다.
 */
public class CustomOAuth2User implements OAuth2User {

  private final OAuth2User delegate;
  /** 우리 DB에 저장된 사용자 username ({provider}_{providerId}) */
  private final String username;

  public CustomOAuth2User(OAuth2User delegate, String username) {
    this.delegate = delegate;
    this.username = username;
  }

  /**
   * 우리 DB의 username을 반환한다. (소셜 플랫폼 ID 대신)
   *
   * @return DB username
   */
  @Override
  public String getName() {
    return username;
  }

  @Override
  public Map<String, Object> getAttributes() {
    return delegate.getAttributes();
  }

  @Override
  public Collection<? extends GrantedAuthority> getAuthorities() {
    return delegate.getAuthorities();
  }
}
