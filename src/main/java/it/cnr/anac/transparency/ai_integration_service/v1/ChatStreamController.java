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
import org.springframework.ai.content.Media;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.util.MimeType;
import org.springframework.util.MimeTypeUtils;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;

import java.io.IOException;
import java.util.*;

/**
 * Controller REST che espone endpoint SSE e non-streaming per lo streaming dei token
 * generati dal modello Ollama tramite Spring AI, con supporto per immagini (multimodale).
 *
 * <p><strong>Come Deep Chat invia le immagini:</strong><br>
 * Deep Chat usa due modalità distinte a seconda della configurazione:
 * <ol>
 *   <li><b>request.handler / custom fetch</b>: Deep Chat invia un JSON con campo
 *       {@code files} contenente le immagini come stringhe base64 (data URL).</li>
 *   <li><b>mixedFiles / form-data</b>: Deep Chat invia una richiesta {@code multipart/form-data}
 *       con un campo {@code files} (i file binari) e un campo {@code message} (il testo).</li>
 * </ol>
 * Questo controller gestisce entrambe le modalità.
 */
@SecurityRequirement(name = "bearer_authentication")
@Tag(
        name = "AI Integration Service Controller",
        description = "Endpoint REST per l'interazione tramite messaggi di testo e immagini con LLM.")
@Slf4j
@RequiredArgsConstructor
@CrossOrigin
@RestController
@RequestMapping(ApiRoutes.BASE_PATH + "/chat")
public class ChatStreamController {

    private final ChatClient chatClient;
    private final ObjectMapper objectMapper;
    private final SseEmitterProperties sseEmitterProperties;

    // -------------------------------------------------------------------------
    // SSE helpers
    // -------------------------------------------------------------------------

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
                .doOnError(java.util.concurrent.TimeoutException.class,
                        e -> log.warn("Timeout durante lo streaming AI"));
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

    // -------------------------------------------------------------------------
    // Image helpers
    // -------------------------------------------------------------------------

    /**
     * Converte una lista di {@link DeepChatFile} (immagini base64 da JSON) in oggetti
     * {@link Media} utilizzabili da Spring AI.
     *
     * <p>Deep Chat invia le immagini con data URL del tipo:
     * {@code data:image/png;base64,iVBORw0K...}
     */
    private List<Media> convertBase64FilesToMedia(List<DeepChatFile> files) {
        if (files == null || files.isEmpty()) return Collections.emptyList();

        List<Media> mediaList = new ArrayList<>();
        for (DeepChatFile file : files) {
            if (!StringUtils.hasText(file.data())) continue;

            String dataUrl = file.data();
            try {
                // Formato atteso: "data:<mimeType>;base64,<dati>"
                if (!dataUrl.startsWith("data:")) {
                    log.warn("File ignorato: data URL non valida (non inizia con 'data:')");
                    continue;
                }
                int commaIdx = dataUrl.indexOf(',');
                if (commaIdx < 0) {
                    log.warn("File ignorato: data URL malformata (nessuna virgola)");
                    continue;
                }

                String meta = dataUrl.substring(5, commaIdx);       // "image/png;base64"
                String base64Data = dataUrl.substring(commaIdx + 1); // "<dati base64>"

                String mimeTypeStr = meta.contains(";")
                        ? meta.substring(0, meta.indexOf(';'))
                        : meta;

                MimeType mimeType = resolveMimeType(mimeTypeStr);
                byte[] bytes = Base64.getDecoder().decode(base64Data);

                // Spring AI 1.0+: Media accetta Resource, non byte[] direttamente
                mediaList.add(new Media(mimeType, new ByteArrayResource(bytes)));
                log.debug("Immagine aggiunta: mimeType={}, bytes={}", mimeType, bytes.length);

            } catch (IllegalArgumentException e) {
                log.warn("File ignorato: impossibile decodificare base64 - {}", e.getMessage());
            }
        }
        return mediaList;
    }

    /**
     * Converte una lista di {@link MultipartFile} (upload binari via form-data) in oggetti
     * {@link Media} utilizzabili da Spring AI.
     */
    private List<Media> convertMultipartFilesToMedia(List<MultipartFile> files) {
        if (files == null || files.isEmpty()) return Collections.emptyList();

        List<Media> mediaList = new ArrayList<>();
        for (MultipartFile file : files) {
            if (file.isEmpty()) continue;

            try {
                String contentType = file.getContentType();
                MimeType mimeType = StringUtils.hasText(contentType)
                        ? resolveMimeType(contentType)
                        : MimeTypeUtils.IMAGE_PNG;

                byte[] bytes = file.getBytes();
                // Spring AI 1.0+: Media accetta Resource, non byte[] direttamente
                mediaList.add(new Media(mimeType, new ByteArrayResource(bytes)));
                log.debug("Immagine multipart aggiunta: name={}, mimeType={}, bytes={}",
                        file.getOriginalFilename(), mimeType, bytes.length);

            } catch (IOException e) {
                log.warn("File multipart ignorato: impossibile leggere i bytes - {}", e.getMessage());
            }
        }
        return mediaList;
    }

    /**
     * Risolve la stringa MIME in un oggetto {@link MimeType}, con fallback a PNG per
     * i tipi non supportati o non immagine.
     */
    private MimeType resolveMimeType(String mimeTypeStr) {
        return switch (mimeTypeStr.toLowerCase(Locale.ROOT)) {
            case "image/png"  -> MimeTypeUtils.IMAGE_PNG;
            case "image/jpeg", "image/jpg" -> MimeTypeUtils.IMAGE_JPEG;
            case "image/gif"  -> MimeTypeUtils.IMAGE_GIF;
            case "image/webp" -> MimeType.valueOf("image/webp");
            default -> {
                log.warn("Tipo MIME '{}' non immagine o non riconosciuto, uso PNG come fallback", mimeTypeStr);
                yield MimeTypeUtils.IMAGE_PNG;
            }
        };
    }

    /**
     * Costruisce un {@link UserMessage} con testo opzionale e lista di {@link Media}.
     * Se non c'è testo, usa un prompt di default per non inviare un messaggio vuoto.
     *
     * <p>Spring AI 1.0+ rimuove il costruttore {@code UserMessage(String, List<Media>)};
     * è necessario usare il builder.
     */
    private UserMessage buildUserMessageWithMedia(String text, List<Media> mediaList) {
        String prompt = StringUtils.hasText(text) ? text : "Descrivi il contenuto di questa immagine.";
        if (mediaList.isEmpty()) {
            return new UserMessage(prompt);
        }
        return UserMessage.builder()
                .text(prompt)
                .media(mediaList)
                .build();
    }

    // -------------------------------------------------------------------------
    // Convertitore messaggi generici (testo puro, senza media)
    // -------------------------------------------------------------------------

    private Message convertToMessage(RoleMessageRequest msg) {
        return switch (msg.role()) {
            case "user"            -> new UserMessage(msg.text());
            case "ai", "assistant" -> new AssistantMessage(msg.text());
            default -> throw new IllegalArgumentException("Unknown role: " + msg.role());
        };
    }

    // -------------------------------------------------------------------------
    // Endpoint streaming (SSE)
    // -------------------------------------------------------------------------

    /**
     * Streaming SSE per messaggi di testo puro con storico conversazione.
     * <p>
     * Eventi inviati:
     * <ul>
     *   <li>{@code token} - chunk di testo incapsulato in JSON</li>
     *   <li>{@code end}   - fine stream</li>
     *   <li>{@code error} - errore durante l'elaborazione</li>
     * </ul>
     */
    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<Chunk>> postStream(@RequestBody StreamRequest body) {
        log.debug("[POST /stream] model={}, messages={}", body.model(),
                Arrays.stream(body.messages())
                        .map(m -> m.role() + ": " + m.text())
                        .toList());

        List<Message> messages = Arrays.stream(body.messages())
                .map(this::convertToMessage)
                .toList();

        var promptSpec = this.chatClient.prompt().messages(messages);
        OllamaChatOptions options = buildOptions(body.model());
        if (options != null) promptSpec = promptSpec.options(options);

        return createSseFlux(promptSpec.stream().content());
    }

    /**
     * Streaming SSE per singolo messaggio GET.
     */
    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<Chunk>> stream(
            @RequestParam(name = "message") String message,
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

    // -------------------------------------------------------------------------
    // Endpoint multimodale con immagini — JSON base64 (Deep Chat request.handler)
    // -------------------------------------------------------------------------

    /**
     * Accetta un messaggio con immagini inviate come data URL base64 nel corpo JSON.
     * <p>
     * Configurazione Deep Chat (lato frontend):
     * <pre>{@code
     * deepChat.request = {
     *   url: "/api/chat/image",
     *   additionalBodyProps: { model: "llava" }
     * };
     * deepChat.images = true;
     * }</pre>
     *
     * <p>Corpo JSON atteso:
     * <pre>{@code
     * {
     *   "messages": [
     *     {
     *       "role": "user",
     *       "text": "Cosa vedi in questa immagine?",
     *       "files": [
     *         { "name": "photo.png", "type": "image", "data": "data:image/png;base64,..." }
     *       ]
     *     }
     *   ],
     *   "model": "llava"
     * }
     * }</pre>
     */
    @PostMapping(
            value = "/image",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public DeepChatResponse chatWithImageJson(@RequestBody ImageRequest body) {
        // Prendiamo l'ultimo messaggio user come quello "corrente" con le immagini
        ImageMessageRequest lastUserMsg = findLastUserMessage(body.messages());

        List<Media> mediaList = lastUserMsg != null
                ? convertBase64FilesToMedia(lastUserMsg.files())
                : Collections.emptyList();

        String textPrompt = lastUserMsg != null ? lastUserMsg.text() : "";

        if (mediaList.isEmpty() && !StringUtils.hasText(textPrompt)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Almeno un'immagine o un testo deve essere fornito.");
        }

        log.info("[POST /api/chat/image JSON] prompt='{}', immagini={}", textPrompt, mediaList.size());

        // Costruisce lo storico (tutti i messaggi precedenti come testo) + ultimo messaggio con media
        List<Message> history = buildHistoryWithoutLast(body.messages());
        UserMessage currentMsg = buildUserMessageWithMedia(textPrompt, mediaList);
        history.add(currentMsg);

        var promptSpec = this.chatClient.prompt().messages(history);
        OllamaChatOptions options = buildOptions(body.model());
        if (options != null) promptSpec = promptSpec.options(options);

        String content = promptSpec.call().content();
        return new DeepChatResponse(content);
    }

    /**
     * Versione streaming SSE del precedente endpoint con immagini JSON.
     */
    @PostMapping(
            value = "/image/stream",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<Chunk>> chatWithImageJsonStream(@RequestBody ImageRequest body) {
        ImageMessageRequest lastUserMsg = findLastUserMessage(body.messages());

        List<Media> mediaList = lastUserMsg != null
                ? convertBase64FilesToMedia(lastUserMsg.files())
                : Collections.emptyList();

        String textPrompt = lastUserMsg != null ? lastUserMsg.text() : "";

        log.info("[POST /api/chat/image/stream JSON] prompt='{}', immagini={}", textPrompt, mediaList.size());

        List<Message> history = buildHistoryWithoutLast(body.messages());
        UserMessage currentMsg = buildUserMessageWithMedia(textPrompt, mediaList);
        history.add(currentMsg);

        var promptSpec = this.chatClient.prompt().messages(history);
        OllamaChatOptions options = buildOptions(body.model());
        if (options != null) promptSpec = promptSpec.options(options);

        return createSseFlux(promptSpec.stream().content());
    }

    // -------------------------------------------------------------------------
    // Endpoint multimodale con immagini — multipart/form-data (Deep Chat mixedFiles)
    // -------------------------------------------------------------------------

    /**
     * Accetta un messaggio con immagini inviate come upload binari via {@code multipart/form-data}.
     * <p>
     * Configurazione Deep Chat (lato frontend):
     * <pre>{@code
     * deepChat.request = { url: "/api/chat/image/upload" };
     * deepChat.mixedFiles = true;   // oppure deepChat.images = { format: "binary" }
     * }</pre>
     *
     * <p>I campi form attesi sono:
     * <ul>
     *   <li>{@code files}   - i file immagine (uno o più)</li>
     *   <li>{@code message} - il testo del prompt (opzionale)</li>
     *   <li>{@code model}   - il modello da usare (opzionale)</li>
     * </ul>
     */
    @PostMapping(
            value = "/image/upload",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public DeepChatResponse chatWithImageUpload(
            @RequestPart(value = "files", required = false) List<MultipartFile> files,
            @RequestParam(value = "message", required = false) String message,
            @RequestParam(value = "model", required = false) String model) {

        List<Media> mediaList = convertMultipartFilesToMedia(files);

        if (mediaList.isEmpty() && !StringUtils.hasText(message)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Almeno un'immagine o un testo deve essere fornito.");
        }

        log.info("[POST /api/chat/image/upload] message='{}', immagini={}", message, mediaList.size());

        UserMessage userMessage = buildUserMessageWithMedia(message, mediaList);
        var promptSpec = this.chatClient.prompt().messages(List.of(userMessage));
        OllamaChatOptions options = buildOptions(model);
        if (options != null) promptSpec = promptSpec.options(options);

        String content = promptSpec.call().content();
        return new DeepChatResponse(content);
    }

    // -------------------------------------------------------------------------
    // Endpoint testo puro (invariati rispetto all'originale)
    // -------------------------------------------------------------------------

    /**
     * Endpoint non-streaming compatibile con client che inviano POST /api/chat.
     */
    @PostMapping(path = {"", "/"}, produces = MediaType.TEXT_PLAIN_VALUE)
    public String chat(
            @RequestBody(required = false) MessageRequest body,
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
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Parametro 'message' obbligatorio");
        }

        var promptSpec = this.chatClient.prompt().user(prompt);
        OllamaChatOptions options = buildOptions(body != null ? body.model() : null);
        if (options != null) promptSpec = promptSpec.options(options);
        return promptSpec.call().content();
    }

    /**
     * Variante per consumare direttamente un body text/plain con il prompt grezzo.
     */
    @PostMapping(path = {"", "/"}, consumes = MediaType.TEXT_PLAIN_VALUE, produces = MediaType.TEXT_PLAIN_VALUE)
    public String chatText(
            @RequestBody String prompt,
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

    // -------------------------------------------------------------------------
    // Utility private
    // -------------------------------------------------------------------------

    private String toJson(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            if (obj instanceof Chunk) return "{\"text\":\"\"}";
            return "{}";
        }
    }

    /**
     * Trova l'ultimo messaggio con ruolo "user" nella lista, che è quello
     * corrente con eventuali immagini allegate.
     */
    private ImageMessageRequest findLastUserMessage(List<ImageMessageRequest> messages) {
        if (messages == null || messages.isEmpty()) return null;
        for (int i = messages.size() - 1; i >= 0; i--) {
            if ("user".equalsIgnoreCase(messages.get(i).role())) {
                return messages.get(i);
            }
        }
        return null;
    }

    /**
     * Costruisce lo storico come lista di {@link Message} escludendo l'ultimo messaggio
     * utente (che viene gestito separatamente con i media).
     */
    private List<Message> buildHistoryWithoutLast(List<ImageMessageRequest> messages) {
        if (messages == null || messages.size() <= 1) return new ArrayList<>();

        List<Message> history = new ArrayList<>();
        int lastUserIdx = -1;
        for (int i = messages.size() - 1; i >= 0; i--) {
            if ("user".equalsIgnoreCase(messages.get(i).role())) {
                lastUserIdx = i;
                break;
            }
        }

        for (int i = 0; i < messages.size(); i++) {
            if (i == lastUserIdx) continue; // saltato: aggiunto dopo con media
            ImageMessageRequest m = messages.get(i);
            switch (m.role().toLowerCase(Locale.ROOT)) {
                case "user"            -> history.add(new UserMessage(
                        StringUtils.hasText(m.text()) ? m.text() : ""));
                case "ai", "assistant" -> history.add(new AssistantMessage(
                        StringUtils.hasText(m.text()) ? m.text() : ""));
                default                -> log.warn("Ruolo sconosciuto ignorato nello storico: {}", m.role());
            }
        }
        return history;
    }

    // -------------------------------------------------------------------------
    // DTO
    // -------------------------------------------------------------------------

    /** Body JSON per POST /api/chat (testo puro). */
    public record MessageRequest(String message, String model) {}

    /** Body JSON per POST /api/chat/stream (storico testo puro). */
    public record StreamRequest(RoleMessageRequest[] messages, String model) {}

    /** Singolo messaggio con ruolo per lo storico testo puro. */
    public record RoleMessageRequest(String role, String text) {}

    /**
     * Singolo file inviato da Deep Chat come data URL base64.
     * Deep Chat include: {@code name}, {@code type} ("image"), {@code data} (data URL).
     */
    public record DeepChatFile(String name, String type, String data) {}

    /**
     * Singolo messaggio con ruolo, testo e lista di file immagine (base64).
     * Usato negli endpoint multimodali JSON.
     */
    public record ImageMessageRequest(String role, String text, List<DeepChatFile> files) {}

    /**
     * Body JSON per POST /api/chat/image e /api/chat/image/stream.
     */
    public record ImageRequest(List<ImageMessageRequest> messages, String model) {}

    /**
     * Risposta JSON restituita dagli endpoint multimodali, compatibile con Deep Chat.
     * Deep Chat si aspetta un campo {@code text} nella risposta.
     */
    public record DeepChatResponse(String text) {}

    /** Wrapper JSON per preservare gli spazi nei chunk SSE: {"text":"..."}. */
    private record Chunk(String text) {}
}