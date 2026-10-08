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

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.mcp.client.common.autoconfigure.properties.McpStreamableHttpClientProperties;
import org.springframework.ai.mcp.client.common.autoconfigure.properties.McpStreamableHttpClientProperties.ConnectionParameters;
import org.springframework.ai.mcp.client.webflux.transport.WebClientStreamableHttpTransport;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.WebClient;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifica che verso le connessioni MCP pubbliche non venga inviato l'header
 * {@code Authorization}, mentre le altre connessioni mantengono il filtro OAuth2.
 * <p>
 * Il transport viene costruito come in {@code StreamableHttpWebFluxTransportAutoConfiguration}
 * (clone del builder "globale" + customizer) e usato da un vero client MCP contro un
 * server HTTP locale che registra gli header ricevuti.
 */
class PublicMcpConnectionsCustomizerTest {

    private static final String BEARER = "Bearer token-che-non-deve-uscire";

    private static final Pattern REQUEST_ID = Pattern.compile("\"id\"\\s*:\\s*(\"[^\"]*\"|\\d+)");

    private final List<String> authorizationHeaders = new CopyOnWriteArrayList<>();

    private HttpServer server;

    private String serverUrl;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/api/mcp", this::handle);
        server.start();
        serverUrl = "http://localhost:" + server.getAddress().getPort();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void publicConnectionDoesNotSendAuthorizationHeader() {
        initializeClient("dvns", List.of("dvns"));

        assertThat(authorizationHeaders).isNotEmpty().allMatch(String::isEmpty);
    }

    @Test
    void otherConnectionsKeepOAuth2Filter() {
        initializeClient("trasparenzai", List.of("dvns"));

        assertThat(authorizationHeaders).isNotEmpty().allMatch(BEARER::equals);
    }

    private void initializeClient(String connectionName, List<String> publicConnections) {
        var properties = new McpStreamableHttpClientProperties();
        properties.getConnections().put(connectionName, new ConnectionParameters(serverUrl, "/api/mcp"));
        var beanFactory = new DefaultListableBeanFactory();
        beanFactory.registerSingleton("streamableHttpProperties", properties);
        var customizer = new PublicMcpConnectionsCustomizer(publicConnections,
                beanFactory.getBeanProvider(McpStreamableHttpClientProperties.class));

        // Equivalente del WebClient.Builder definito in SecurityConfig
        var oauth2WebClientBuilder = WebClient.builder().filter((request, next) ->
                next.exchange(ClientRequest.from(request).header("Authorization", BEARER).build()));

        var transportBuilder = WebClientStreamableHttpTransport
                .builder(oauth2WebClientBuilder.clone().baseUrl(serverUrl))
                .endpoint("/api/mcp")
                .jsonMapper(new JacksonMcpJsonMapper(new JsonMapper()));
        customizer.customize(connectionName, transportBuilder);

        try (var client = McpClient.sync(transportBuilder.build())
                .requestTimeout(Duration.ofSeconds(5))
                .build()) {
            client.initialize();
        }
    }

    private void handle(HttpExchange exchange) throws IOException {
        authorizationHeaders.add(
                exchange.getRequestHeaders().getOrDefault("Authorization", List.of("")).getFirst());
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        if (!"POST".equals(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(405, -1);
            return;
        }
        var id = REQUEST_ID.matcher(body);
        if (!body.contains("\"initialize\"") || !id.find()) {
            // notifiche (es. notifications/initialized)
            exchange.sendResponseHeaders(202, -1);
            return;
        }
        byte[] response = """
                {"jsonrpc":"2.0","id":%s,"result":{"protocolVersion":"2025-06-18","capabilities":{},\
                "serverInfo":{"name":"test","version":"0.0.1"}}}""".formatted(id.group(1))
                .getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, response.length);
        try (var out = exchange.getResponseBody()) {
            out.write(response);
        }
    }

}
