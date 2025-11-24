package it.cnr.anac.transparency.ai_integration_service.web;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.http.MediaType;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

import java.io.IOException;
import java.time.Duration;
import java.util.Objects;

/**
 * Controller REST che espone un endpoint SSE per lo streaming dei token
 * generati dal modello Ollama tramite Spring AI.
 */
@RestController
@RequestMapping("/api/chat")
public class ChatStreamController {

    private final ChatClient chatClient;
    private static final Logger log = LoggerFactory.getLogger(ChatStreamController.class);

    public ChatStreamController(ChatClient.Builder chatClientBuilder) {
        // costruiamo un client predefinito (config in application.properties)
        this.chatClient = chatClientBuilder.build();
    }

    /**
     * Avvia lo streaming SSE dei token della risposta del modello.
     * Eventi inviati:
     *  - name: "token" (chunk di testo)
     *  - name: "end" (fine stream)
     *  - name: "error" (errore durante l'elaborazione)
     */
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
                        // Inoltra ogni token/chunk come evento denominato "token"
                        emitter.send(SseEmitter.event()
                            .name("token")
                            .data(chunk, MediaType.TEXT_PLAIN)
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
                        emitter.send(SseEmitter.event().name("end").data(""));
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
}
