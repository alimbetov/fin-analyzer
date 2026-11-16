package kz.fin.backend.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;

import java.util.*;

/**
 * An absent or incomplete Keycloak configuration must NEVER make the financial
 * API public. It starts DENY_ALL; real JWT mode requires issuer, JWKS and audience.
 *
 * Tokens must come from a dedicated Keycloak client scope with fin_access claims.
 */
@Configuration
public class FinancialApiSecurity {
    @Bean
    public CorsConfigurationSource finCors(
            @Value("${fin.security.ui-origin:}") String origin){
        CorsConfiguration config=new CorsConfiguration();
        if(!origin.isBlank()){
            if(!origin.startsWith("https://") &&
                    !origin.matches("http://localhost(:[0-9]{2,5})?"))
                throw new IllegalStateException("FIN_UI_ORIGIN_MUST_BE_EXPLICIT");
            config.setAllowedOrigins(List.of(origin));
        }
        config.setAllowedMethods(List.of("GET","POST","OPTIONS"));
        config.setAllowedHeaders(List.of("Authorization","Content-Type"));
        config.setAllowCredentials(false);
        config.setMaxAge(600L);
        UrlBasedCorsConfigurationSource source=new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/v1/financial/**",config);
        return source;
    }

    @Bean
    public SecurityFilterChain finSecurity(
            HttpSecurity http,
            @Value("${fin.security.enabled:false}") boolean enabled,
            @Value("${fin.security.issuer:}") String issuer,
            @Value("${fin.security.jwks-uri:}") String jwks,
            @Value("${fin.security.audience:}") String audience) throws Exception {
        http.csrf(AbstractHttpConfigurer::disable)
                .cors(Customizer.withDefaults())
                .sessionManagement(sm->sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS));
        if(!enabled){
            http.authorizeHttpRequests(auth->auth.anyRequest().denyAll());
            return http.build();
        }
        if(issuer.isBlank()||jwks.isBlank()||audience.isBlank()
                ||!issuer.startsWith("https://")||!jwks.startsWith("https://")){
            throw new IllegalStateException("FIN_OIDC_REQUIRED_CONFIGURATION_INCOMPLETE");
        }
        NimbusJwtDecoder decoder=NimbusJwtDecoder.withJwkSetUri(jwks)
                .jwsAlgorithm(org.springframework.security.oauth2.jose.jws.SignatureAlgorithm.RS256)
                .build();
        OAuth2TokenValidator<Jwt> expectedAudience=jwt->{
            if(jwt.getAudience().contains(audience))
                return org.springframework.security.oauth2.core.OAuth2TokenValidatorResult.success();
            return org.springframework.security.oauth2.core.OAuth2TokenValidatorResult.failure(
                    new org.springframework.security.oauth2.core.OAuth2Error(
                            "invalid_token","FIN_OIDC_AUDIENCE_MISMATCH",null));
        };
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(issuer), expectedAudience));
        http.authorizeHttpRequests(auth->auth
                .requestMatchers(HttpMethod.OPTIONS,"/api/v1/financial/**").permitAll()
                .requestMatchers("/api/v1/financial/**").authenticated()
                .anyRequest().denyAll())
                .oauth2ResourceServer(resource->resource.jwt(jwt->jwt.decoder(decoder)));
        return http.build();
    }
}
