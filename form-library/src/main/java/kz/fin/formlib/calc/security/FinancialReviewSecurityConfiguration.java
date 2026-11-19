package kz.fin.formlib.calc.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.web.SecurityFilterChain;

import java.net.URI;

/**
 * CALC-3C.3-A: dedicated, opt-in JWT boundary for methodology review.
 *
 * The legacy form editor had no Spring Security filters: this change MUST NOT
 * silently introduce a broken login flow into the existing web UI. The new
 * /internal/fin-calc/financial-review/** namespace is denied by default.
 * When enabled, a trusted issuer + pinned JWK URI + dedicated audience are
 * mandatory. A bearer token is verified with Nimbus before reaching services.
 * There is deliberately no signed S2 approval/write endpoint.
 */
@Configuration(proxyBeanMethods=false)
public class FinancialReviewSecurityConfiguration {
  public static final String PATH="/internal/fin-calc/financial-review/**";

  @Bean @Order(1)
  SecurityFilterChain reviewChain(HttpSecurity http,
      @Value("${fin.financial-review.api-enabled:false}") boolean enabled) throws Exception {
    http.securityMatcher(PATH)
      .csrf(AbstractHttpConfigurer::disable)
      .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
      .httpBasic(AbstractHttpConfigurer::disable)
      .formLogin(AbstractHttpConfigurer::disable);
    if(enabled) {
      http.authorizeHttpRequests(a->a.anyRequest().authenticated())
          .oauth2ResourceServer(o->o.jwt(Customizer.withDefaults()));
    }else {
      http.authorizeHttpRequests(a->a.anyRequest().denyAll());
    }
    return http.build();
  }

  @Bean @Order(2)
  SecurityFilterChain legacyCompatibilityChain(HttpSecurity http) throws Exception {
    // Scope unchanged: pre-existing local FIN form endpoints are not part of
    // the new finance-approval boundary. Their authorization is separate work.
    http.csrf(AbstractHttpConfigurer::disable)
      .httpBasic(AbstractHttpConfigurer::disable)
      .formLogin(AbstractHttpConfigurer::disable)
      .authorizeHttpRequests(a->a.anyRequest().permitAll());
    return http.build();
  }

  @Bean
  @ConditionalOnProperty(prefix="fin.financial-review",name="api-enabled",havingValue="true")
  JwtDecoder financialReviewJwtDecoder(
      @Value("${fin.financial-review.issuer:}") String issuer,
      @Value("${fin.financial-review.jwk-set-uri:}") String jwkSetUri,
      @Value("${fin.financial-review.audience:}") String audience) {
    if(issuer.isBlank()||jwkSetUri.isBlank()||audience.isBlank())
      throw new IllegalStateException("CALC3C3_IAM_TRUST_CONFIGURATION_REQUIRED");
    URI issuerUri=URI.create(issuer),jwkUri=URI.create(jwkSetUri);
    if(!"https".equalsIgnoreCase(issuerUri.getScheme())
        ||!"https".equalsIgnoreCase(jwkUri.getScheme())
        ||issuerUri.getHost()==null||jwkUri.getHost()==null)
      throw new IllegalStateException("CALC3C3_IAM_HTTPS_TRUST_REQUIRED");
    NimbusJwtDecoder decoder=NimbusJwtDecoder.withJwkSetUri(jwkSetUri).build();
    OAuth2TokenValidator<Jwt> audienceValidator=jwt ->
      jwt.getAudience()!=null&&jwt.getAudience().contains(audience)
        ? OAuth2TokenValidatorResult.success()
        : OAuth2TokenValidatorResult.failure(new OAuth2Error(
            "invalid_token","CALC3C3_IAM_AUDIENCE_REJECTED",null));
    decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
      JwtValidators.createDefaultWithIssuer(issuer),audienceValidator));
    return decoder;
  }
}
