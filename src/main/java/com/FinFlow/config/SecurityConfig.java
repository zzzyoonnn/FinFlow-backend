package com.FinFlow.config;

import com.FinFlow.config.jwt.JwtAuthenticationFilter;
import com.FinFlow.config.jwt.JwtAuthorizationFilter;
import com.FinFlow.domain.UserEnum;
import com.FinFlow.util.CustomResponseUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

// @Slf4j : 테스트 시 에러 발생
@Configuration
public class SecurityConfig {

  private final Logger log = LoggerFactory.getLogger(getClass());

  @Bean   // IoC 컨테이너에 BCryptPasswordEncoder() 객체가 등록됨
  public BCryptPasswordEncoder bCryptPasswordEncoder() {
    return new BCryptPasswordEncoder();
  }

  @Bean // AuthenticationManager를 명시적으로 빈으로 등록
  public AuthenticationManager authenticationManager(
          AuthenticationConfiguration authenticationConfiguration) throws Exception {
    return authenticationConfiguration.getAuthenticationManager();
  }

  // JWT 서버 사용(Session 사용 X)
  @Bean
  public SecurityFilterChain securityFilterChain(HttpSecurity http, AuthenticationManager authenticationManager)
          throws Exception {
    http
            .headers(headers -> headers.frameOptions(frame -> frame.disable())) // iframe 허용
            .csrf(csrf -> csrf.disable()) // Postman 등 테스트용
            .cors(cors -> cors.configurationSource(corsConfigurationSource())) // CORS 허용
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS)) // 세션 사용 안 함
            .formLogin(form -> form.disable()) // React, 앱용
            .httpBasic(basic -> basic.disable()) // 브라우저 인증 팝업 비활성화
            .addFilterAt(  // 필터 적용
                    new JwtAuthenticationFilter(authenticationManager),
                    UsernamePasswordAuthenticationFilter.class
            )
            .addFilterAfter(new JwtAuthorizationFilter(authenticationManager),
                    UsernamePasswordAuthenticationFilter.class)

            // Exception 가로채기
            .exceptionHandling(exception -> exception
                    .authenticationEntryPoint((request, response, authException) -> {
                      CustomResponseUtil.fail(response, "로그인을 진행해 주세요.", HttpStatus.UNAUTHORIZED);
                    })
            )

            // 권한 실패
            .exceptionHandling(exception -> exception
                    .accessDeniedHandler(((request, response, e) -> {
                      CustomResponseUtil.fail(response, "권한이 없습니다.", HttpStatus.FORBIDDEN);
                    })))

            .authorizeHttpRequests(auth -> auth //권한 설정
                    .requestMatchers("/api/s/**").authenticated()   // 로그인 필요
                    .requestMatchers("/api/admin/**").hasRole(UserEnum.ADMIN.name()) // ADMIN 권한 필요
                    .anyRequest().permitAll()   // 그 외는 모두 허용
            );

    return http.build();
  }

  @Bean
  public CorsConfigurationSource corsConfigurationSource() {
    CorsConfiguration configuration = new CorsConfiguration();

    // Origin
    configuration.addAllowedMethod("*");        // 모든 HTTP 메서드 허용

    // Method
    configuration.addAllowedOriginPattern("http://localhost:3000");

    // Header
    configuration.addAllowedHeader("*");        // 모든 헤더 허용
    configuration.addExposedHeader("Authorization");

    // Credentials
    configuration.setAllowCredentials(true);    // 쿠키/인증정보 허용

    UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
    source.registerCorsConfiguration("/**", configuration);
    return source;
  }
}
