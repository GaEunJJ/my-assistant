package com.myassistant.config;

import com.myassistant.oauth2.OAuth2SuccessHandler;
import com.myassistant.security.JwtAuthenticationWebFilter;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.SecurityWebFiltersOrder;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.http.HttpStatus;
import org.springframework.security.web.server.DelegatingServerAuthenticationEntryPoint;
import org.springframework.security.web.server.ServerAuthenticationEntryPoint;
import org.springframework.security.web.server.authentication.HttpStatusServerEntryPoint;
import org.springframework.security.web.server.authentication.RedirectServerAuthenticationEntryPoint;
import org.springframework.security.web.server.context.NoOpServerSecurityContextRepository;
import org.springframework.security.web.server.util.matcher.ServerWebExchangeMatchers;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.reactive.CorsConfigurationSource;
import org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource;

import java.util.List;

/**
 * Spring Security 인증/인가 설정 (WebFlux + JWT Stateless)
 */
@Configuration
@EnableWebFluxSecurity
@RequiredArgsConstructor
public class SecurityConfig {

  private final JwtAuthenticationWebFilter jwtAuthenticationWebFilter;
  private final OAuth2SuccessHandler oauth2SuccessHandler;

  // 프런트엔드 도메인 (로컬 개발 기본값: Next.js dev 서버)
  @Value("${app.cors.allowed-origins:http://localhost:3000}")
  private List<String> allowedOrigins;

  @Bean
  public SecurityWebFilterChain securityWebFilterChain(ServerHttpSecurity http) {
    return http
        .csrf(ServerHttpSecurity.CsrfSpec::disable)
        .cors(cors -> cors.configurationSource(corsConfigurationSource()))
        // JWT는 무상태 — 세션 컨텍스트 저장 비활성화
        .securityContextRepository(NoOpServerSecurityContextRepository.getInstance())
        .authorizeExchange(exchanges -> exchanges
            // 일반 인증 엔드포인트
            .pathMatchers("/api/auth/**").permitAll()
            // OAuth2 로그인 시작 및 콜백 엔드포인트 허용
            .pathMatchers("/oauth2/authorization/**", "/login/oauth2/code/**").permitAll()
            .anyExchange().authenticated()
        )
        // /api/** 미인증 요청은 로그인 페이지(302) 대신 401 반환
        // 기본값(oauth2Login의 /login 리다이렉트)이면 axios가 HTML을 받아 JSON처럼 처리하게 됨
        .exceptionHandling(exceptions -> exceptions
            .authenticationEntryPoint(apiAuthenticationEntryPoint())
        )
        .httpBasic(ServerHttpSecurity.HttpBasicSpec::disable)
        .formLogin(ServerHttpSecurity.FormLoginSpec::disable)
        // OAuth2 소셜 로그인 설정
        // Spring Security 6.5+는 OAuth2LoginSpec.userInfoEndpoint() API가 제거되어,
        // ReactiveOAuth2UserService<OAuth2UserRequest, OAuth2User> 타입의 빈(CustomOAuth2UserService)을
        // 컨텍스트에서 자동으로 찾아 사용한다 (명시적 와이어링 불필요)
        .oauth2Login(oauth2 -> oauth2
            .authenticationSuccessHandler(oauth2SuccessHandler)
        )
        .addFilterAt(jwtAuthenticationWebFilter, SecurityWebFiltersOrder.AUTHENTICATION)
        .build();
  }

  /**
   * /api/** 경로는 401, 그 외 경로는 기존처럼 로그인 페이지로 리다이렉트하는 인증 진입점을 만든다.
   *
   * @return 경로별로 위임하는 인증 진입점
   */
  private ServerAuthenticationEntryPoint apiAuthenticationEntryPoint() {
    DelegatingServerAuthenticationEntryPoint entryPoint = new DelegatingServerAuthenticationEntryPoint(
        new DelegatingServerAuthenticationEntryPoint.DelegateEntry(
            ServerWebExchangeMatchers.pathMatchers("/api/**"),
            new HttpStatusServerEntryPoint(HttpStatus.UNAUTHORIZED)
        )
    );
    entryPoint.setDefaultEntryPoint(new RedirectServerAuthenticationEntryPoint("/login"));
    return entryPoint;
  }

  private CorsConfigurationSource corsConfigurationSource() {
    CorsConfiguration config = new CorsConfiguration();
    config.setAllowedOrigins(allowedOrigins);
    config.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
    config.setAllowedHeaders(List.of("Authorization", "Content-Type"));
    config.setAllowCredentials(true);

    UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
    source.registerCorsConfiguration("/**", config);
    return source;
  }

  @Bean
  public PasswordEncoder passwordEncoder() {
    return new BCryptPasswordEncoder();
  }
}
