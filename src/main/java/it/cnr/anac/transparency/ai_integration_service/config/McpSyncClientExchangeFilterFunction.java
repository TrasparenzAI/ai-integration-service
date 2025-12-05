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
package it.cnr.anac.transparency.ai_integration_service.config;

import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.ClientCredentialsOAuth2AuthorizedClientProvider;
import org.springframework.security.oauth2.client.OAuth2AuthorizationContext;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.reactive.function.client.ServletOAuth2AuthorizedClientExchangeFilterFunction;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.*;
import reactor.core.publisher.Mono;
import java.util.function.Consumer;

/**
 * A wrapper around Spring Security's
 * {@link ServletOAuth2AuthorizedClientExchangeFilterFunction}, which adds OAuth2
 * {@code access_token}s to requests sent to the MCP server.
 * <p>
 * The end goal is to use access_token that represent the end-user's permissions. Those
 * tokens are obtained using the {@code authorization_code} OAuth2 flow, but it requires a
 * user to be present and using their browser.
 * <p>
 * By default, the MCP tools are initialized on app startup, so some requests to the MCP
 * server happen, to establish the session (/sse), and to send the {@code initialize} and
 * e.g. {@code tools/list} requests. For this to work, we need an access_token, but we
 * cannot get one using the authorization_code flow (no user is present). Instead, we rely
 * on the OAuth2 {@code client_credentials} flow for machine-to-machine communication.
 */
@Component
public class McpSyncClientExchangeFilterFunction implements ExchangeFilterFunction {

    private final ClientCredentialsOAuth2AuthorizedClientProvider clientCredentialTokenProvider =
            new ClientCredentialsOAuth2AuthorizedClientProvider();

    private final ServletOAuth2AuthorizedClientExchangeFilterFunction delegate;

    private final ClientRegistrationRepository clientRegistrationRepository;

    // Must match registration id in property
    // spring.security.oauth2.client.registration.<REGISTRATION-ID>.authorization-grant-type=authorization_code
    private static final String AUTHORIZATION_CODE_CLIENT_REGISTRATION_ID = "authserver";

    // Must match registration id in property
    // spring.security.oauth2.client.registration.<REGISTRATION-ID>.authorization-grant-type=client_credentials
    private static final String CLIENT_CREDENTIALS_CLIENT_REGISTRATION_ID = "authserver-client-credentials";

    public McpSyncClientExchangeFilterFunction(OAuth2AuthorizedClientManager clientManager,
                                               ClientRegistrationRepository clientRegistrationRepository) {
        this.delegate = new ServletOAuth2AuthorizedClientExchangeFilterFunction(clientManager);
        this.delegate.setDefaultClientRegistrationId(AUTHORIZATION_CODE_CLIENT_REGISTRATION_ID);
        this.clientRegistrationRepository = clientRegistrationRepository;
    }

    /**
     * Add an {@code access_token} to the request sent to the MCP server.
     * <p>
     * When serving HTTP requests (Servlet context), prefer forwarding the end-user
     * Bearer JWT to the MCP server (on-behalf-of). If no user JWT is present,
     * fall back to OAuth2 {@code client_credentials} for machine-to-machine access.
     * During application startup (no request context), also use {@code client_credentials}.
     */
    @Override
    public Mono<ClientResponse> filter(ClientRequest request, ExchangeFunction next) {
        var authentication = SecurityContextHolder.getContext().getAuthentication();

        if (authentication instanceof JwtAuthenticationToken jwtAuth && jwtAuth.getToken() != null) {
            // Forward the user's JWT to the MCP server
            var userJwt = jwtAuth.getToken().getTokenValue();
            var reqWithUserJwt = ClientRequest.from(request)
                    .headers(headers -> headers.setBearerAuth(userJwt))
                    .build();
            return next.exchange(reqWithUserJwt);
        }

        // Fallback to client_credentials (startup or no user JWT available)
        var accessToken = getClientCredentialsAccessToken();
        var requestWithToken = ClientRequest.from(request)
                .headers(headers -> headers.setBearerAuth(accessToken))
                .build();
        return next.exchange(requestWithToken);
    }

    private String getClientCredentialsAccessToken() {
        var clientRegistration = this.clientRegistrationRepository
                .findByRegistrationId(CLIENT_CREDENTIALS_CLIENT_REGISTRATION_ID);

        var authRequest = OAuth2AuthorizationContext.withClientRegistration(clientRegistration)
                .principal(new AnonymousAuthenticationToken("client-credentials-client", "client-credentials-client",
                        AuthorityUtils.createAuthorityList("ROLE_ANONYMOUS")))
                .build();
        return this.clientCredentialTokenProvider.authorize(authRequest).getAccessToken().getTokenValue();
    }

    /**
     * Configure a {@link WebClient} to use this exchange filter function.
     */
    public Consumer<WebClient.Builder> configuration() {
        return builder -> builder.defaultRequest(this.delegate.defaultRequest()).filter(this);
    }

}