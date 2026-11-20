package kz.fin.formlib.calc.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.web.SecurityFilterChain;

import java.net.URI;

/**
 * Dedicated fail-closed data-read namespace. The historic editor's permitAll
 * chain must NOT authorize financial data exports. A signed JWT with pinned
 * issuer, JWK and audience is mandatory in enabled environments.
 */
@Configuration(proxyBeanMethods=false)
public class FinFactDataSecurityConfiguration {
  public static final String PATH="/internal/fin-fact/data/**";
  public static final String AUDIENCE="fin-fact-calculation-data/v1";

  @Bean @Order(0)
  SecurityFilterChain calculationFactDataChain(HttpSecurity http,
      @Value("$"+"{fin.fact.data-reader.enabled:false}") boolean enabled,
      @Value("$"+"{fin.fact.data-reader.issuer:}") String issuer,
      @Value("$"+"{fin.fact.data-reader.jwk-set-uri:}") String jwkUri,
      @Value("$"+"{fin.fact.data-reader.audience:}") String audience) throws Exception {
    http.securityMatcher(PATH)
      .csrf(AbstractHttpConfigurer::disable)
      .sessionManagement(s->s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
      .httpBasic(AbstractHttpConfigurer::disable)
      .formLogin(AbstractHttpConfigurer::disable);
    if(!enabled){
      http.authorizeHttpRequests(a->a.anyRequest().denyAll());
      return http.build();
    }
    if(issuer.isBlank()||jwkUri.isBlank()||!AUDIENCE.equals(audience))
      throw new IllegalStateException("FIN_FACT_DATA_IAM_CONFIGURATION_REQUIRED");
    URI issuerUrl=URI.create(issuer),jwkUrl=URI.create(jwkUri);
    if(!"https".equalsIgnoreCase(issuerUrl.getScheme())
        ||!"https".equalsIgnoreCase(jwkUrl.getScheme())
        ||issuerUrl.getHost()==null||jwkUrl.getHost()==null)
      throw new IllegalStateException("FIN_FACT_DATA_HTTPS_IAM_REQUIRED");
    NimbusJwtDecoder decoder=NimbusJwtDecoder.withJwkSetUri(jwkUri).build();
    OAuth2TokenValidator<Jwt> validAudience=token->
      token.getAudience()!=null&&token.getAudience().contains(AUDIENCE)
        ?OAuth2TokenValidatorResult.success()
        :OAuth2TokenValidatorResult.failure(new OAuth2Error(
          "invalid_token","FIN_FACT_DATA_AUDIENCE_MISMATCH",null));
    decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
      JwtValidators.createDefaultWithIssuer(issuer),validAudience));
    http.authorizeHttpRequests(a->a
      .requestMatchers(HttpMethod.GET,PATH).authenticated()
      .anyRequest().denyAll())
      .oauth2ResourceServer(o->o.jwt(jwt->jwt.decoder(decoder)));
    return http.build();
  }
}
