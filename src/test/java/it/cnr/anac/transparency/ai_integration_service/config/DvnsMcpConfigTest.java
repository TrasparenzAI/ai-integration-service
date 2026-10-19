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
import io.modelcontextprotocol.spec.McpClientTransport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.mcp.client.common.autoconfigure.NamedClientMcpTransport;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.util.TestPropertyValues;
import org.springframework.cloud.autoconfigure.RefreshAutoConfiguration;
import org.springframework.cloud.context.scope.refresh.RefreshScope;
import org.springframework.context.ApplicationContext;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Verifica l'attivazione dell'MCP server di DoveVannoINostriSoldi tramite
 * {@code ai.mcp.dvns.enabled}, la sua rilettura dopo un refresh dello scope
 * (equivalente di {@code /actuator/refresh}) e che verso di esso non venga inviato
 * l'header {@code Authorization}, anche in presenza del {@link WebClient.Builder}
 * con filtro OAuth2 definito in {@code SecurityConfig}.
 * <p>
 * Il bean {@code dvnsMcpTransport} e' {@code @RefreshScope}, quindi e' esposto tramite
 * un proxy che perde il tipo generico {@code List<NamedClientMcpTransport>}: nei test
 * viene recuperato per nome e non per tipo generico.
 * <p>
 * Il transport viene usato da un vero client MCP contro un server HTTP locale che
 * registra gli header ricevuti. Viene verificato anche che le risposte piu' grandi
 * del limite di default dei codec (256KB) vengano lette: il payload grande sta nella
 * risposta a {@code tools/list} e non in quella a {@code initialize}, che il client
 * MCP scrive per intero nel log (una riga da centinaia di KB rallenta di minuti
 * l'elaborazione dei log della CI GitHub).
 */
class DvnsMcpConfigTest {

    private static final String BEARER = "Bearer token-che-non-deve-uscire";

    private static final String BEAN_NAME = "dvnsMcpTransport";

    private static final Pattern REQUEST_ID = Pattern.compile("\"id\"\\s*:\\s*(\"[^\"]*\"|\\d+)");

    /** Risposta piu' grande del limite di default dei codec (256KB). */
    private static final int LARGE_RESPONSE_SIZE = 512 * 1024;

    private final List<String> authorizationHeaders = new CopyOnWriteArrayList<>();

    private HttpServer server;

    private ApplicationContextRunner contextRunner;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/api/mcp", this::handle);
        server.start();
        contextRunner = new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(RefreshAutoConfiguration.class))
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
        contextRunner.run(context -> assertThat(transports(context)).isEmpty());
    }

    @Test
    void explicitlyDisabledYieldsNoTransports() {
        contextRunner.withPropertyValues("ai.mcp.dvns.enabled=false")
                .run(context -> assertThat(transports(context)).isEmpty());
    }

    @Test
    void enabledServerReceivesNoAuthorizationHeader() {
        contextRunner.withPropertyValues("ai.mcp.dvns.enabled=true").run(context -> {
            var transports = transports(context);
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

    @Test
    void refreshEnablesServerWhenPropertyChangesToTrue() {
        contextRunner.withPropertyValues("ai.mcp.dvns.enabled=false").run(context -> {
            assertThat(transports(context)).isEmpty();

            // senza refresh il valore precedente resta in cache
            TestPropertyValues.of("ai.mcp.dvns.enabled=true").applyTo(context);
            assertThat(transports(context)).isEmpty();

            context.getBean(RefreshScope.class).refreshAll();

            assertThat(transports(context)).singleElement()
                    .extracting(NamedClientMcpTransport::name).isEqualTo(DvnsMcpConfig.CONNECTION_NAME);
        });
    }

    @Test
    void refreshDisablesServerWhenPropertyChangesToFalse() {
        contextRunner.withPropertyValues("ai.mcp.dvns.enabled=true").run(context -> {
            assertThat(transports(context)).hasSize(1);

            TestPropertyValues.of("ai.mcp.dvns.enabled=false").applyTo(context);
            context.getBean(RefreshScope.class).refreshAll();

            assertThat(transports(context)).isEmpty();
        });
    }

    @Test
    void readsResponsesLargerThanDefaultCodecLimit() {
        contextRunner.withPropertyValues("ai.mcp.dvns.enabled=true").run(context -> {
            try (var client = McpClient.sync(transport(context))
                    .requestTimeout(Duration.ofSeconds(5))
                    .build()) {
                client.initialize();
                assertThat(client.listTools().tools()).singleElement()
                        .satisfies(tool -> assertThat(tool.description()).hasSize(LARGE_RESPONSE_SIZE));
            }
        });
    }

    @Test
    void responsesOverMaxInMemorySizeAreNotRead() {
        contextRunner.withPropertyValues("ai.mcp.dvns.enabled=true", "ai.mcp.dvns.max-in-memory-size=256KB")
                .run(context -> {
                    try (var client = McpClient.sync(transport(context))
                            .requestTimeout(Duration.ofSeconds(2))
                            .build()) {
                        client.initialize();
                        assertThatThrownBy(client::listTools).isInstanceOf(RuntimeException.class);
                    }
                });
    }

    /** Recupero per nome: il proxy dello scope refresh non espone il tipo generico della lista. */
    @SuppressWarnings("unchecked")
    private static List<NamedClientMcpTransport> transports(ApplicationContext context) {
        return context.getBean(BEAN_NAME, List.class);
    }

    private static McpClientTransport transport(ApplicationContext context) {
        return transports(context).getFirst().transport();
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
        if (!id.find()) {
            // notifiche (es. notifications/initialized)
            exchange.sendResponseHeaders(202, -1);
            return;
        }
        String result = body.contains("\"tools/list\"")
                ? """
                {"tools":[{"name":"large","description":"%s","inputSchema":{"type":"object"}}]}"""
                .formatted("x".repeat(LARGE_RESPONSE_SIZE))
                : """
                {"protocolVersion":"2025-06-18","capabilities":{"tools":{}},\
                "serverInfo":{"name":"test","version":"0.0.1"}}""";
        byte[] response = """
                {"jsonrpc":"2.0","id":%s,"result":%s}""".formatted(id.group(1), result)
                .getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, response.length);
        try (var out = exchange.getResponseBody()) {
            out.write(response);
        }
    }

}
