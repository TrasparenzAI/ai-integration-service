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
package it.cnr.anac.transparency.ai_integration_service.v1;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import it.cnr.anac.transparency.ai_integration_service.config.SseEmitterProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;

import java.util.Arrays;
import java.util.List;

/**
 * Controller REST che espone un endpoint SSE per lo streaming dei token
 * generati dal modello Ollama tramite Spring AI.
 */
@SecurityRequirement(name = "bearer_authentication")
@Tag(
        name = "AI Integration Service Controller",
        description = "Endpoint REST per l'interazione tramite messaggi di testo con LLM.")
@Slf4j
@RequiredArgsConstructor
@CrossOrigin
@RestController
@RequestMapping(ApiRoutes.BASE_PATH + "/chat")
public class ChatStreamController {

    private final ChatClient chatClient;
    private final ObjectMapper objectMapper;
    private final SseEmitterProperties sseEmitterProperties;


    /**
     * Trasforma il Flux di stringhe in un Flux di ServerSentEvent, gestendo
     * correttamente la terminazione e gli errori.
     */
    private Flux<ServerSentEvent<Chunk>> createSseFlux(Flux<String> stringFlux) {
        return stringFlux
                .map(chunk -> ServerSentEvent.<Chunk>builder()
                        .event("token")
                        .data(new Chunk(chunk))
                        .build())
                .concatWith(Flux.just(ServerSentEvent.<Chunk>builder()
                        .event("end")
                        .build()))
                .onErrorResume(err -> {
                    String msg = err.getMessage();
                    if (msg == null) msg = err.getClass().getSimpleName();
                    log.error("Errore durante lo streaming AI: {}", msg, err);
                    return Flux.just(ServerSentEvent.<Chunk>builder()
                            .event("error")
                            .data(new Chunk(msg))
                            .build());
                })
                .timeout(sseEmitterProperties.getTimeout())
                .doOnError(java.util.concurrent.TimeoutException.class, e -> log.warn("Timeout durante lo streaming AI"));
    }

    /**
     * Costruisce le OllamaChatOptions solo se il modello è specificato,
     * altrimenti ritorna null (Spring AI userà il default configurato).
     */
    private OllamaChatOptions buildOptions(String model) {
        if (!StringUtils.hasText(model)) return null;
        log.debug("Modello richiesto a runtime: {}", model);
        return OllamaChatOptions.builder().model(model).build();
    }

    /**
     * Eventi inviati:
     * - name: "token" (chunk di testo incapsulato in JSON)
     * - name: "end" (fine stream)
     * - name: "error" (errore durante l'elaborazione)
     */
    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<Chunk>> postStream(@RequestBody StreamRequest body) {
        List<Message> messages = Arrays.stream(body.messages())
                .map(this::convertToMessage)
                .toList();

        var promptSpec = this.chatClient.prompt().messages(messages);
        OllamaChatOptions options = buildOptions(body.model());
        if (options != null) promptSpec = promptSpec.options(options);

        return createSseFlux(promptSpec.stream().content());
    }

    private Message convertToMessage(RoleMessageRequest msg) {
        return switch (msg.role()) {
            case "user" -> new UserMessage(msg.text());
            case "ai", "assistant" -> new AssistantMessage(msg.text());
            default -> throw new IllegalArgumentException("Unknown role: " + msg.role());
        };
    }

    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<Chunk>> stream(@RequestParam(name = "message") String message,
                                               @RequestParam(name = "model", required = false) String model) {
        if (!StringUtils.hasText(message)) {
            return Flux.just(ServerSentEvent.<Chunk>builder()
                    .event("error")
                    .data(new Chunk("Parametro 'message' obbligatorio"))
                    .build());
        }

        var promptSpec = this.chatClient.prompt().user(message);
        OllamaChatOptions options = buildOptions(model);
        if (options != null) promptSpec = promptSpec.options(options);

        return createSseFlux(promptSpec.stream().content());
    }

    /**
     * Endpoint non-streaming compatibile con client che inviano POST /api/chat.
     * Accetta sia JSON {"message":"..."} sia il parametro di query/form "message".
     * Ritorna la risposta completa in testo semplice.
     */
    // Accetta sia /api/chat che /api/chat/
    @PostMapping(path = {"", "/"}, produces = MediaType.TEXT_PLAIN_VALUE)
    public String chat(@RequestBody(required = false) MessageRequest body,
                       @RequestParam(name = "message", required = false) String message) {
        if (log.isInfoEnabled()) {
            log.info("[POST /api/chat] message(param)='{}', message(body)='{}'",
                    message, (body != null ? body.message() : null));
        }

        String prompt = null;
        if (StringUtils.hasText(message)) {
            prompt = message;
        } else if (body != null && StringUtils.hasText(body.message())) {
            prompt = body.message();
        }

        if (!StringUtils.hasText(prompt)) {
            // coerentemente con WebClient che si aspetta 4xx in caso di input mancante
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Parametro 'message' obbligatorio");
        }

        // Chiamata sincrona non-streaming
        var promptSpec = this.chatClient.prompt().user(prompt);
        OllamaChatOptions options = buildOptions(body != null ? body.model() : null);
        if (options != null) promptSpec = promptSpec.options(options);
        return promptSpec.call().content();
    }

    /**
     * Variante per consumare direttamente un body text/plain con il prompt grezzo.
     * Utile per client che inviano il corpo come testo invece che JSON.
     */
    @PostMapping(path = {"", "/"}, consumes = MediaType.TEXT_PLAIN_VALUE, produces = MediaType.TEXT_PLAIN_VALUE)
    public String chatText(@RequestBody String prompt,
                           @RequestParam(name = "message", required = false) String messageParam,
                           @RequestParam(name = "model", required = false) String model) {
        if (log.isInfoEnabled()) {
            log.info("[POST /api/chat text/plain] message(param)='{}', bodyLength={}",
                    messageParam, (prompt != null ? prompt.length() : null));
        }

        String effective = null;
        if (StringUtils.hasText(messageParam)) {
            effective = messageParam;
        } else if (StringUtils.hasText(prompt)) {
            effective = prompt;
        }

        if (!StringUtils.hasText(effective)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Parametro 'message' obbligatorio");
        }

        var promptSpec = this.chatClient.prompt().user(effective);
        OllamaChatOptions options = buildOptions(model);
        if (options != null) promptSpec = promptSpec.options(options);
        return promptSpec.call().content();
    }

    /**
     * Endpoint diagnostico per verificare rapidamente che il mapping sia attivo.
     */
    @GetMapping(path = {"", "/"}, produces = MediaType.TEXT_PLAIN_VALUE)
    public String ping() {
        if (log.isDebugEnabled()) {
            log.debug("[GET /api/chat] ping");
        }
        return "OK";
    }

    private String toJson(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            // Fallback minimale
            if (obj instanceof Chunk) {
                return "{\"text\":\"\"}";
            }
            return "{}";
        }
    }

    /**
     * DTO minimale per il body JSON della richiesta POST.
     * Il campo {@code model} è opzionale: se presente sovrascrive il default configurato.
     */
    public record MessageRequest(String message, String model) {
    }

    /**
     * DTO minimale per il body JSON della richiesta POST con STREAM.
     * Il campo {@code model} è opzionale: se presente sovrascrive il default configurato.
     */
    public record StreamRequest(RoleMessageRequest[] messages, String model) {
    }

    public record RoleMessageRequest(String role, String text) {
    }

    // Wrapper JSON per preservare gli spazi nei chunk: {"c":"..."}
    private record Chunk(String text) {
    }
}