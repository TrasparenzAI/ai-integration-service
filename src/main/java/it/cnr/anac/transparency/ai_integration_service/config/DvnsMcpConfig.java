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

import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.mcp.client.common.autoconfigure.NamedClientMcpTransport;
import org.springframework.ai.mcp.client.webflux.transport.WebClientStreamableHttpTransport;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;

/**
 * Connessione all'MCP server pubblico di DoveVannoINostriSoldi (dati di finanza
 * pubblica, read-only, senza autenticazione), attivabile con {@code ai.mcp.dvns.enabled}.
 * <p>
 * L'autoconfigurazione Spring AI raccoglie tutti i bean {@code List<NamedClientMcpTransport>},
 * quindi il server viene aggiunto agli altri MCP server senza passare dalle
 * {@code spring.ai.mcp.client.streamable-http.connections}. Il transport usa un
 * {@link WebClient} dedicato e non il {@link WebClient.Builder} di {@code SecurityConfig}:
 * in questo modo al servizio esterno non viene inviato nessun token OAuth2
 * (ne' il JWT dell'utente ne' quello {@code client_credentials}).
 */
@Slf4j
@Configuration
@ConditionalOnBooleanProperty("ai.mcp.dvns.enabled")
public class DvnsMcpConfig {

    public static final String CONNECTION_NAME = "dvns";

    @Bean
    List<NamedClientMcpTransport> dvnsMcpTransport(
            @Value("${ai.mcp.dvns.url}") String url,
            @Value("${ai.mcp.dvns.endpoint}") String endpoint,
            ObjectProvider<JsonMapper> jsonMapperProvider) {
        log.info("MCP server DoveVannoINostriSoldi abilitato: {}{}", url, endpoint);
        var transport = WebClientStreamableHttpTransport.builder(WebClient.builder().baseUrl(url))
                .endpoint(endpoint)
                .jsonMapper(new JacksonMcpJsonMapper(jsonMapperProvider.getIfAvailable(JsonMapper::new)))
                .build();
        return List.of(new NamedClientMcpTransport(CONNECTION_NAME, transport));
    }

}
