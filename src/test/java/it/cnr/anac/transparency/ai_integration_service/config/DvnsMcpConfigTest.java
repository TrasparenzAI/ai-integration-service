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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.mcp.client.common.autoconfigure.NamedClientMcpTransport;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.ResolvableType;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.WebClient;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifica l'attivazione dell'MCP server di DoveVannoINostriSoldi tramite
 * {@code ai.mcp.dvns.enabled} e che verso di esso non venga inviato l'header
 * {@code Authorization}, anche in presenza del {@link WebClient.Builder} con filtro
 * OAuth2 definito in {@code SecurityConfig}.
 * <p>
 * Il transport viene usato da un vero client MCP contro un server HTTP locale che
 * registra gli header ricevuti.
 */
class DvnsMcpConfigTest {

    private static final String BEARER = "Bearer token-che-non-deve-uscire";

    private static final Pattern REQUEST_ID = Pattern.compile("\"id\"\\s*:\\s*(\"[^\"]*\"|\\d+)");

    private static final ResolvableType TRANSPORTS_TYPE =
            ResolvableType.forClassWithGenerics(List.class, NamedClientMcpTransport.class);

    private final List<String> authorizationHeaders = new CopyOnWriteArrayList<>();

    private HttpServer server;

    private ApplicationContextRunner contextRunner;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/api/mcp", this::handle);
        server.start();
        contextRunner = new ApplicationContextRunner()
                .withUserConfiguration(DvnsMcpConfig.class)
                // Equivalente del WebClient.Builder definito in SecurityConfig
                .withBean(WebClient.Builder.class, () -> WebClient.builder().filter((request, next) ->
                        next.exchange(ClientRequest.from(request).header("Authorization", BEARER).build())))
                .withPropertyValues(
                        "ai.mcp.dvns.url=http://localhost:" + server.getAddress().getPort(),
                        "ai.mcp.dvns.endpoint=/api/mcp");
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void disabledByDefault() {
        contextRunner.run(context -> assertThat(context.getBeanNamesForType(TRANSPORTS_TYPE)).isEmpty());
    }

    @Test
    void enabledServerReceivesNoAuthorizationHeader() {
        contextRunner.withPropertyValues("ai.mcp.dvns.enabled=true").run(context -> {
            @SuppressWarnings("unchecked")
            var transports = (List<NamedClientMcpTransport>) context.getBeanProvider(TRANSPORTS_TYPE).getObject();
            assertThat(transports).singleElement()
                    .extracting(NamedClientMcpTransport::name).isEqualTo(DvnsMcpConfig.CONNECTION_NAME);

            try (var client = McpClient.sync(transports.getFirst().transport())
                    .requestTimeout(Duration.ofSeconds(5))
                    .build()) {
                client.initialize();
            }

            assertThat(authorizationHeaders).isNotEmpty().allMatch(String::isEmpty);
        });
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
