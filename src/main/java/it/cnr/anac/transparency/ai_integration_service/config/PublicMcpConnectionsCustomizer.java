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

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.mcp.client.common.autoconfigure.properties.McpStreamableHttpClientProperties;
import org.springframework.ai.mcp.client.webflux.transport.WebClientStreamableHttpTransport;
import org.springframework.ai.mcp.customizer.McpClientCustomizer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.List;
import java.util.Objects;

/**
 * Esclude dall'autenticazione OAuth2 le connessioni MCP verso server pubblici esterni.
 * <p>
 * L'autoconfigurazione Spring AI costruisce il transport di ogni connessione
 * Streamable HTTP clonando il {@link WebClient.Builder} definito in
 * {@code SecurityConfig}, che tramite {@link McpSyncClientExchangeFilterFunction}
 * aggiunge il JWT dell'utente o un token {@code client_credentials}. Per le
 * connessioni elencate in {@code ai.mcp.public-connections} il builder viene
 * sostituito con uno privo di filtri, così che nessun token Keycloak venga inviato
 * a servizi di terze parti.
 */
@Slf4j
@Component
public class PublicMcpConnectionsCustomizer
        implements McpClientCustomizer<WebClientStreamableHttpTransport.Builder> {

    private final List<String> publicConnections;

    private final ObjectProvider<McpStreamableHttpClientProperties> streamableHttpProperties;

    public PublicMcpConnectionsCustomizer(
            @Value("${ai.mcp.public-connections:}") List<String> publicConnections,
            ObjectProvider<McpStreamableHttpClientProperties> streamableHttpProperties) {
        this.publicConnections = publicConnections;
        this.streamableHttpProperties = streamableHttpProperties;
    }

    @Override
    public void customize(String name, WebClientStreamableHttpTransport.Builder transportBuilder) {
        if (!publicConnections.contains(name)) {
            return;
        }
        var connection = streamableHttpProperties.getObject().getConnections().get(name);
        String url = Objects.requireNonNull(connection.url(), "Missing url for server named " + name);
        log.info("Connessione MCP '{}' ({}) configurata come pubblica: nessun token OAuth2 inviato", name, url);
        transportBuilder.webClientBuilder(WebClient.builder().baseUrl(url));
    }

}
