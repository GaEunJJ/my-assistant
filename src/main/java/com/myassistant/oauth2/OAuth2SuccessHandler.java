package com.myassistant.oauth2;

import com.myassistant.security.JwtProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.server.WebFilterExchange;
import org.springframework.security.web.server.authentication.ServerAuthenticationSuccessHandler;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.net.URI;

/**
 * OAuth2 로그인 성공 시 우리 서버의 JWT를 발급하고 프론트엔드로 리다이렉트하는 핸들러.
 * 프론트엔드는 ?token= 쿼리 파라미터로 JWT를 수신하여 로컬 저장소에 보관한다.
 */
@Component
@RequiredArgsConstructor
public class OAuth2SuccessHandler implements ServerAuthenticationSuccessHandler {

  private final JwtProvider jwtProvider;

  /** 소셜 로그인 완료 후 리다이렉트할 프론트엔드 URL */
  @Value("${app.oauth2.redirect-uri:http://localhost:3000/auth/callback}")
  private String redirectUri;

  /**
   * 소셜 로그인 성공 시 JWT를 발급하여 프론트엔드로 302 리다이렉트한다.
   *
   * @param webFilterExchange 현재 요청/응답 컨텍스트
   * @param authentication    성공한 인증 객체 (CustomOAuth2User 포함)
   * @return 응답 완료 신호
   */
  @Override
  public Mono<Void> onAuthenticationSuccess(WebFilterExchange webFilterExchange, Authentication authentication) {
    // CustomOAuth2User.getName()이 DB username을 반환하므로 바로 JWT 발급 가능
    String username = authentication.getName();
    String jwt = jwtProvider.generateToken(username);

    URI location = URI.create(redirectUri + "?token=" + jwt);
    webFilterExchange.getExchange().getResponse().setStatusCode(HttpStatus.FOUND);
    webFilterExchange.getExchange().getResponse().getHeaders().setLocation(location);
    return webFilterExchange.getExchange().getResponse().setComplete();
  }
}
