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

import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.http.MediaType;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

import java.io.IOException;
import java.time.Duration;
import java.util.Arrays;

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

//    public ChatStreamController(ChatClient.Builder chatClientBuilder, ObjectMapper objectMapper) {
//        // costruiamo un client predefinito (config in application.properties)
//        this.chatClient = chatClientBuilder.build();
//        this.objectMapper = objectMapper;
//    }

    /**
     * Avvia lo streaming SSE dei token della risposta del modello.
     * Eventi inviati:
     *  - name: "token" (chunk di testo)
     *  - name: "end" (fine stream)
     *  - name: "error" (errore durante l'elaborazione)
     */
    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter postStream(@RequestBody StreamRequest body) {
        return stream(Arrays.stream(body.messages).findAny().map(RoleMessageRequest::text).orElse(""));
    }

    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@RequestParam(name = "message") String message) {
        if (!StringUtils.hasText(message)) {
            // errore immediato con SSE minimale (chiudiamo subito)
            SseEmitter bad = new SseEmitter(0L);
            try {
                bad.send(SseEmitter.event().name("error").data("Parametro 'message' obbligatorio"));
            } catch (IOException ignored) {
            }
            bad.complete();
            return bad;
        }

        // Timeout: 2 minuti per conversazione (0L = infinito, ma meglio evitare connessioni orfane)
        SseEmitter emitter = new SseEmitter(Duration.ofMinutes(2).toMillis());

        // Sottoscrizione allo stream dei contenuti (token) del modello
        var subscription = this.chatClient
            .prompt()
            .user(message)
            .stream()
            .content()
            .subscribe(
                chunk -> {
                    try {
                        // Avvolgi il chunk in JSON per preservare spazi iniziali/finali attraverso SSE
                        String json = toJson(new Chunk(chunk));
                        emitter.send(SseEmitter.event()
                                .name("token")
                                .data(json, MediaType.APPLICATION_JSON)
                        );
                    } catch (IOException e) {
                        // Se il client ha chiuso la connessione o c'è I/O error, interrompi lo stream
                        try {
                            emitter.send(SseEmitter.event().name("error").data("Connessione client interrotta"));
                        } catch (IOException ignored) {}
                        emitter.complete();
                    }
                },
                err -> {
                    try {
                        String msg = err.getMessage();
                        if (msg == null) msg = err.getClass().getSimpleName();
                        emitter.send(SseEmitter.event()
                                .name("error")
                                .data(msg, MediaType.TEXT_PLAIN)
                        );
                    } catch (IOException ignored) {
                    } finally {
                        emitter.complete();
                    }
                },
                () -> {
                    try {
                        emitter.send(SseEmitter.event().name("end"));
                    } catch (IOException ignored) {
                    } finally {
                        emitter.complete();
                    }
                }
            );

        // Se il client chiude la connessione, annulla la sottoscrizione
        emitter.onCompletion(subscription::dispose);
        emitter.onTimeout(() -> {
            try {
                emitter.send(SseEmitter.event().name("error").data("Timeout connessione"));
            } catch (IOException ignored) {
            } finally {
                subscription.dispose();
                emitter.complete();
            }
        });

        return emitter;
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
        return this.chatClient
                .prompt()
                .user(prompt)
                .call()
                .content();
    }

    /**
     * Variante per consumare direttamente un body text/plain con il prompt grezzo.
     * Utile per client che inviano il corpo come testo invece che JSON.
     */
    @PostMapping(path = {"", "/"}, consumes = MediaType.TEXT_PLAIN_VALUE, produces = MediaType.TEXT_PLAIN_VALUE)
    public String chatText(@RequestBody String prompt,
                           @RequestParam(name = "message", required = false) String messageParam) {
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

        return this.chatClient
                .prompt()
                .user(effective)
                .call()
                .content();
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

    /**
     * DTO minimale per il body JSON della richiesta POST.
     */
    public record MessageRequest(String message) {}
    /**
     * DTO minimale per il body JSON della richiesta POST con STREAM.
     */
    public record StreamRequest(RoleMessageRequest[] messages) {}

    public record RoleMessageRequest(String role, String text) {}

    // Wrapper JSON per preservare gli spazi nei chunk: {"c":"..."}
    private record Chunk(String text) {}

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
}
