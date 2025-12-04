/*
 * Copyright (C) 2025 Consiglio Nazionale delle Ricerche
 *
 *     This program is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU Affero General Public License as
 *     published by the Free Software Foundation, either version 3 of the
 *     License, or (at your option) any later version.
 *
 *     This program is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *     GNU Affero General Public License for more details.
 *
 *     You should have received a copy of the GNU Affero General Public License
 *     along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package it.cnr.anac.transparency.ai_integration_service.security;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.web.SecurityFilterChain;

@Slf4j
@EnableWebSecurity
@EnableMethodSecurity
@Configuration
@EnableConfigurationProperties(Oauth2Properties.class)
public class SecurityConfig {
    private final Oauth2Properties oauth2Properties;

    public SecurityConfig(Oauth2Properties oauth2Properties) {
        this.oauth2Properties = oauth2Properties;
    }

    @Bean
    public SecurityFilterChain configure(HttpSecurity http) throws Exception {
        log.info("Enabling security config");
        if (oauth2Properties.isEnabled()) {
            http.authorizeHttpRequests(expressionInterceptUrlRegistry -> {
                expressionInterceptUrlRegistry
                        .requestMatchers(HttpMethod.OPTIONS).permitAll()
                        .requestMatchers(HttpMethod.GET, "/actuator/*").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api-docs/**", "/swagger-ui/**").permitAll();
                oauth2Properties
                        .getRoles()
                        .forEach((key, value) ->
                                expressionInterceptUrlRegistry
                                        .requestMatchers(HttpMethod.valueOf(key)).hasAnyRole(value)
                        );
                oauth2Properties
                        .getUrls()
                        .forEach((key, value) ->
                                expressionInterceptUrlRegistry
                                        .requestMatchers(key).hasAnyRole(value)
                        );
                expressionInterceptUrlRegistry
                        .anyRequest()
                        .permitAll();
            });
        }
        return http
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(config -> config.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .oauth2ResourceServer(httpSecurityOAuth2ResourceServerConfigurer -> httpSecurityOAuth2ResourceServerConfigurer.jwt(jwtConfigurer -> jwtConfigurer.jwtAuthenticationConverter(
                        new RolesClaimConverter(new JwtGrantedAuthoritiesConverter())
                )))
                .build();
    }
}