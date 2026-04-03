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
import it.cnr.anac.transparency.ai_integration_service.clients.WhisperClient;
import it.cnr.anac.transparency.ai_integration_service.config.SseEmitterProperties;
import it.cnr.anac.transparency.ai_integration_service.util.ByteArrayMultipartFile;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
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
import java.util.stream.Collectors;

/**
 * Controller REST per l'interazione con Ollama tramite Spring AI,
 * con supporto per testo, immagini e audio.
 *
 * <p><strong>Gestione audio:</strong><br>
 * I file audio vengono trascritti direttamente tramite Whisper <em>nel controller</em>,
 * prima di coinvolgere il modello LLM. Il testo trascritto viene incluso nel prompt
 * come testo normale. Questo approccio evita tre problemi:
 * <ul>
 *   <li>Ollama non supporta input audio nativi</li>
 *   <li>Passare base64 audio nel prompt causa l'errore "image: unknown format"</li>
 *   <li>I modelli VL non invocano tool in modo affidabile con payload enormi</li>
 * </ul>
 *
 * <p><strong>Come Deep Chat invia i file:</strong><br>
 * <ol>
 *   <li><b>JSON base64</b>: campo {@code files} con data URL base64 per immagini/audio.</li>
 *   <li><b>multipart/form-data</b>: campo {@code files} con file binari.</li>
 * </ol>
 */
@SecurityRequirement(name = "bearer_authentication")
@Tag(
        name = "AI Integration Service Controller",
        description = "Endpoint REST per l'interazione tramite messaggi di testo, immagini e audio con LLM.")
@Slf4j
@RequiredArgsConstructor
@CrossOrigin
@RestController
@RequestMapping(ApiRoutes.BASE_PATH + "/chat")
public class ChatStreamController {

    private final ChatClient chatClient;
    private final ObjectMapper objectMapper;
    private final SseEmitterProperties sseEmitterProperties;
    private final WhisperClient whisperClient;
    // -------------------------------------------------------------------------
    // SSE helpers
    // -------------------------------------------------------------------------

    private Flux<ServerSentEvent<Chunk>> createSseFlux(Flux<ChatResponse> chatResponseFlux) {
        return chatResponseFlux
                .map(chatResponse -> {
                    var generations = Optional.ofNullable(chatResponse.getResults())
                            .orElse(Collections.emptyList());
                    var thinking = generations.stream()
                            .map(Generation::getMetadata)
                            .filter(cgm -> cgm.get("thinking") != null)
                            .map(cgm -> cgm.get("thinking"))
                            .map(String::valueOf)
                            .collect(Collectors.joining());
                    return ServerSentEvent.<Chunk>builder()
                            .event("token")
                            .data(new Chunk(thinking, chatResponse.getResult().getOutput().getText()))
                            .build();
                })
                .concatWith(Flux.just(ServerSentEvent.<Chunk>builder()
                        .event("end")
                        .build()))
                .onErrorResume(err -> {
                    String msg = err.getMessage();
                    if (msg == null) msg = err.getClass().getSimpleName();
                    log.error("Errore durante lo streaming AI: {}", msg, err);
                    return Flux.just(ServerSentEvent.<Chunk>builder()
                            .event("error")
                            .data(new Chunk(null, msg))
                            .build());
                })
                .timeout(sseEmitterProperties.getTimeout())
                .doOnError(java.util.concurrent.TimeoutException.class,
                        e -> log.warn("Timeout durante lo streaming AI"));
    }

    private OllamaChatOptions buildOptions(String model) {
        return OllamaChatOptions.builder()
                .model(model)
                //.enableThinking()
                .temperature(0.2) // Fondamentale: bassa temperatura per non rompere i tag del thinking
                .build();
    }

    // -------------------------------------------------------------------------
    // Audio helpers — trascrizione diretta via Whisper
    // -------------------------------------------------------------------------

    private boolean isAudioFile(DeepChatFile file) {
        return file.type() != null
                && file.type().toLowerCase(Locale.ROOT).startsWith("audio/");
    }

    private boolean isAudioMultipartFile(MultipartFile file) {
        String ct = file.getContentType();
        return ct != null && ct.toLowerCase(Locale.ROOT).startsWith("audio/");
    }

    /**
     * Trascrive un file audio DeepChat (data URL base64) tramite Whisper.
     * Restituisce il testo o {@code null} in caso di errore.
     */
    private String transcribeAudioFile(DeepChatFile file, String language) {
        if (!StringUtils.hasText(file.data())) return null;

        String dataUrl = file.data();
        int commaIdx = dataUrl.indexOf(',');
        String base64 = commaIdx >= 0 ? dataUrl.substring(commaIdx + 1) : dataUrl;

        try {
            byte[] audioBytes = Base64.getDecoder().decode(base64);
            MultipartFile multipartFile = new ByteArrayMultipartFile(
                    audioBytes,
                    StringUtils.hasText(file.name()) ? file.name() : "audio.wav",
                    StringUtils.hasText(file.type()) ? file.type() : "audio/wav"
            );
            String transcription = whisperClient.transcribe(multipartFile, language, "txt").trim();
            log.info("Trascrizione completata: '{}' → {} caratteri", file.name(), transcription.length());
            return transcription;
        } catch (Exception e) {
            log.error("Errore trascrizione '{}': {}", file.name(), e.getMessage(), e);
            return null;
        }
    }

    /**
     * Trascrive un file audio multipart tramite Whisper.
     * Restituisce il testo o {@code null} in caso di errore.
     */
    private String transcribeMultipartAudio(MultipartFile file, String language) {
        try {
            String transcription = whisperClient.transcribe(file, language, "txt").trim();
            log.info("Trascrizione multipart completata: '{}' → {} caratteri",
                    file.getOriginalFilename(), transcription.length());
            return transcription;
        } catch (Exception e) {
            log.error("Errore trascrizione multipart '{}': {}", file.getOriginalFilename(), e.getMessage(), e);
            return null;
        }
    }

    // -------------------------------------------------------------------------
    // Image helpers
    // -------------------------------------------------------------------------

    /**
     * Converte i file immagine (escludendo audio) in oggetti {@link Media}.
     */
    private List<Media> convertBase64FilesToMedia(List<DeepChatFile> files) {
        if (files == null || files.isEmpty()) return Collections.emptyList();

        List<Media> mediaList = new ArrayList<>();
        for (DeepChatFile file : files) {
            if (!StringUtils.hasText(file.data())) continue;
            if (isAudioFile(file)) continue;

            String dataUrl = file.data();
            try {
                if (!dataUrl.startsWith("data:")) { log.warn("data URL non valida per '{}'", file.name()); continue; }
                int commaIdx = dataUrl.indexOf(',');
                if (commaIdx < 0) { log.warn("data URL malformata per '{}'", file.name()); continue; }

                String meta = dataUrl.substring(5, commaIdx);
                String base64Data = dataUrl.substring(commaIdx + 1);
                String mimeTypeStr = meta.contains(";") ? meta.substring(0, meta.indexOf(';')) : meta;

                MimeType mimeType = resolveImageMimeType(mimeTypeStr);
                byte[] bytes = Base64.getDecoder().decode(base64Data);
                mediaList.add(new Media(mimeType, new ByteArrayResource(bytes)));
                log.debug("Immagine aggiunta: mimeType={}, bytes={}", mimeType, bytes.length);
            } catch (IllegalArgumentException e) {
                log.warn("Impossibile decodificare base64 per '{}': {}", file.name(), e.getMessage());
            }
        }
        return mediaList;
    }

    /**
     * Converte i file immagine multipart (escludendo audio) in oggetti {@link Media}.
     */
    private List<Media> convertMultipartFilesToMedia(List<MultipartFile> files) {
        if (files == null || files.isEmpty()) return Collections.emptyList();

        List<Media> mediaList = new ArrayList<>();
        for (MultipartFile file : files) {
            if (file.isEmpty() || isAudioMultipartFile(file)) continue;
            try {
                String ct = file.getContentType();
                MimeType mimeType = StringUtils.hasText(ct) ? resolveImageMimeType(ct) : MimeTypeUtils.IMAGE_PNG;
                mediaList.add(new Media(mimeType, new ByteArrayResource(file.getBytes())));
                log.debug("Immagine multipart aggiunta: {}", file.getOriginalFilename());
            } catch (IOException e) {
                log.warn("Impossibile leggere bytes per '{}': {}", file.getOriginalFilename(), e.getMessage());
            }
        }
        return mediaList;
    }

    private MimeType resolveImageMimeType(String mimeTypeStr) {
        return switch (mimeTypeStr.toLowerCase(Locale.ROOT)) {
            case "image/png"           -> MimeTypeUtils.IMAGE_PNG;
            case "image/jpeg",
                 "image/jpg"           -> MimeTypeUtils.IMAGE_JPEG;
            case "image/gif"           -> MimeTypeUtils.IMAGE_GIF;
            case "image/webp"          -> MimeType.valueOf("image/webp");
            default -> { log.warn("MIME '{}' non riconosciuto, uso PNG", mimeTypeStr); yield MimeTypeUtils.IMAGE_PNG; }
        };
    }

    private UserMessage buildUserMessageWithMedia(String text, List<Media> mediaList) {
        String prompt = StringUtils.hasText(text) ? text : "Descrivi il contenuto di questa immagine.";
        if (mediaList.isEmpty()) return new UserMessage(prompt);
        return UserMessage.builder().text(prompt).media(mediaList).build();
    }

    // -------------------------------------------------------------------------
    // Costruzione UserMessage con trascrizione audio integrata
    // -------------------------------------------------------------------------

    /**
     * Costruisce il {@link UserMessage} finale.
     *
     * <p>Se sono presenti file audio, li trascrive subito tramite Whisper e
     * appende la trascrizione al testo del prompt. Il modello LLM riceve
     * <em>solo testo e immagini</em> — mai byte audio o base64 grezzo.
     */
    private UserMessage buildUserMessage(String textPrompt, List<DeepChatFile> files, String language) {
        if (files == null || files.isEmpty()) {
            return new UserMessage(StringUtils.hasText(textPrompt) ? textPrompt : "");
        }

        List<DeepChatFile> audioFiles = files.stream().filter(this::isAudioFile).toList();
        List<DeepChatFile> imageFiles = files.stream().filter(f -> !isAudioFile(f)).toList();

        StringBuilder promptBuilder = new StringBuilder();
        if (StringUtils.hasText(textPrompt)) promptBuilder.append(textPrompt);

        for (DeepChatFile audioFile : audioFiles) {
            String transcription = transcribeAudioFile(audioFile, language);
            if (!promptBuilder.isEmpty()) promptBuilder.append("\n\n");
            if (StringUtils.hasText(transcription)) {
                promptBuilder.append("[Trascrizione audio '")
                        .append(audioFile.name()).append("']: ")
                        .append(transcription);
            } else {
                promptBuilder.append("[Audio '").append(audioFile.name())
                        .append("': errore di trascrizione]");
            }
        }

        List<Media> mediaList = convertBase64FilesToMedia(imageFiles);
        log.debug("UserMessage: audio={}, immagini={}, promptLen={}",
                audioFiles.size(), mediaList.size(), promptBuilder.length());
        return buildUserMessageWithMedia(promptBuilder.toString(), mediaList);
    }

    /**
     * Versione multipart di {@link #buildUserMessage}.
     */
    private UserMessage buildUserMessageFromMultipart(String textPrompt, List<MultipartFile> files, String language) {
        if (files == null || files.isEmpty()) {
            return new UserMessage(StringUtils.hasText(textPrompt) ? textPrompt : "");
        }

        List<MultipartFile> audioFiles = files.stream().filter(this::isAudioMultipartFile).toList();
        List<MultipartFile> imageFiles = files.stream().filter(f -> !isAudioMultipartFile(f)).toList();

        StringBuilder promptBuilder = new StringBuilder();
        if (StringUtils.hasText(textPrompt)) promptBuilder.append(textPrompt);

        for (MultipartFile audioFile : audioFiles) {
            String transcription = transcribeMultipartAudio(audioFile, language);
            if (!promptBuilder.isEmpty()) promptBuilder.append("\n\n");
            if (StringUtils.hasText(transcription)) {
                promptBuilder.append("[Trascrizione audio '")
                        .append(audioFile.getOriginalFilename()).append("']: ")
                        .append(transcription);
            } else {
                promptBuilder.append("[Audio '").append(audioFile.getOriginalFilename())
                        .append("': errore di trascrizione]");
            }
        }

        List<Media> mediaList = convertMultipartFilesToMedia(imageFiles);
        return buildUserMessageWithMedia(promptBuilder.toString(), mediaList);
    }

    // -------------------------------------------------------------------------
    // Convertitore messaggi storici
    // -------------------------------------------------------------------------

    private Message convertToMessage(RoleMessageRequest msg) {
        return switch (msg.role()) {
            case "user"            -> new UserMessage(msg.text());
            case "ai", "assistant" -> new AssistantMessage(msg.text());
            default -> throw new IllegalArgumentException("Unknown role: " + msg.role());
        };
    }

    private String toJson(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            if (obj instanceof Chunk) return "{\"text\":\"\"}";
            return "{}";
        }
    }

    private ImageMessageRequest findLastUserMessage(List<ImageMessageRequest> messages) {
        if (messages == null || messages.isEmpty()) return null;
        for (int i = messages.size() - 1; i >= 0; i--) {
            if ("user".equalsIgnoreCase(messages.get(i).role())) return messages.get(i);
        }
        return null;
    }

    private List<Message> buildHistoryWithoutLast(List<ImageMessageRequest> messages) {
        if (messages == null || messages.size() <= 1) return new ArrayList<>();

        int lastUserIdx = -1;
        for (int i = messages.size() - 1; i >= 0; i--) {
            if ("user".equalsIgnoreCase(messages.get(i).role())) { lastUserIdx = i; break; }
        }

        List<Message> history = new ArrayList<>();
        for (int i = 0; i < messages.size(); i++) {
            if (i == lastUserIdx) continue;
            ImageMessageRequest m = messages.get(i);
            switch (m.role().toLowerCase(Locale.ROOT)) {
                case "user"            -> history.add(new UserMessage(StringUtils.hasText(m.text()) ? m.text() : ""));
                case "ai", "assistant" -> history.add(new AssistantMessage(StringUtils.hasText(m.text()) ? m.text() : ""));
                default                -> log.warn("Ruolo sconosciuto: {}", m.role());
            }
        }
        return history;
    }

    // -------------------------------------------------------------------------
    // Endpoint streaming testo puro
    // -------------------------------------------------------------------------

    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<Chunk>> postStream(@RequestBody StreamRequest body) {
        log.debug("[POST /stream] model={}", body.model());
        List<Message> messages = Arrays.stream(body.messages())
                .filter(rmr -> Optional.ofNullable(rmr.text()).filter(s -> !s.isEmpty()).isPresent())
                .map(this::convertToMessage)
                .toList();
        var promptSpec = this.chatClient.prompt().messages(messages);
        OllamaChatOptions options = buildOptions(body.model());
        if (options != null) promptSpec = promptSpec.options(options);
        return createSseFlux(promptSpec.stream().chatResponse());
    }

    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<Chunk>> stream(
            @RequestParam(name = "message") String message,
            @RequestParam(name = "model", required = false) String model) {
        if (!StringUtils.hasText(message)) {
            return Flux.just(ServerSentEvent.<Chunk>builder().event("error").data(new Chunk(null, "Parametro 'message' obbligatorio")).build());
        }
        var promptSpec = this.chatClient.prompt().user(message);
        OllamaChatOptions options = buildOptions(model);
        if (options != null) promptSpec = promptSpec.options(options);
        return createSseFlux(promptSpec.stream().chatResponse());
    }

    // -------------------------------------------------------------------------
    // Endpoint multimodale JSON base64
    // -------------------------------------------------------------------------

    @PostMapping(value = "/image", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public DeepChatResponse chatWithImageJson(@RequestBody ImageRequest body) {
        ImageMessageRequest lastUserMsg = findLastUserMessage(body.messages());
        String textPrompt = lastUserMsg != null ? lastUserMsg.text() : "";
        List<DeepChatFile> files = lastUserMsg != null && lastUserMsg.files() != null ? lastUserMsg.files() : Collections.emptyList();

        if (files.isEmpty() && !StringUtils.hasText(textPrompt)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Almeno un file o un testo deve essere fornito.");
        }

        log.info("[POST /api/chat/image JSON] prompt='{}', file={}", textPrompt, files.size());
        List<Message> history = buildHistoryWithoutLast(body.messages());
        history.add(buildUserMessage(textPrompt, files, "it"));

        var promptSpec = this.chatClient.prompt().messages(history);
        OllamaChatOptions options = buildOptions(body.model());
        if (options != null) promptSpec = promptSpec.options(options);
        return new DeepChatResponse(promptSpec.call().content());
    }

    @PostMapping(value = "/image/stream", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<Chunk>> chatWithImageJsonStream(@RequestBody ImageRequest body) {
        ImageMessageRequest lastUserMsg = findLastUserMessage(body.messages());
        String textPrompt = lastUserMsg != null ? lastUserMsg.text() : "";
        List<DeepChatFile> files = lastUserMsg != null && lastUserMsg.files() != null ? lastUserMsg.files() : Collections.emptyList();

        log.info("[POST /api/chat/image/stream JSON] prompt='{}', file={}", textPrompt, files.size());

        // Trascrizione audio avviene qui, PRIMA dello stream
        List<Message> history = buildHistoryWithoutLast(body.messages());
        history.add(buildUserMessage(textPrompt, files, "it"));

        var promptSpec = this.chatClient.prompt().messages(history);
        OllamaChatOptions options = buildOptions(body.model());
        if (options != null) promptSpec = promptSpec.options(options);
        return createSseFlux(promptSpec.stream().chatResponse());
    }

    // -------------------------------------------------------------------------
    // Endpoint multimodale multipart
    // -------------------------------------------------------------------------

    @PostMapping(value = "/image/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public DeepChatResponse chatWithImageUpload(
            @RequestPart(value = "files", required = false) List<MultipartFile> files,
            @RequestParam(value = "message", required = false) String message,
            @RequestParam(value = "model", required = false) String model) {

        if ((files == null || files.isEmpty()) && !StringUtils.hasText(message)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Almeno un file o un testo deve essere fornito.");
        }

        log.info("[POST /api/chat/image/upload] message='{}', files={}", message, files != null ? files.size() : 0);
        var promptSpec = this.chatClient.prompt().messages(List.of(buildUserMessageFromMultipart(message, files, "it")));
        OllamaChatOptions options = buildOptions(model);
        if (options != null) promptSpec = promptSpec.options(options);
        return new DeepChatResponse(promptSpec.call().content());
    }

    // -------------------------------------------------------------------------
    // Endpoint testo puro
    // -------------------------------------------------------------------------

    @PostMapping(path = {"", "/"}, produces = MediaType.TEXT_PLAIN_VALUE)
    public String chat(
            @RequestBody(required = false) MessageRequest body,
            @RequestParam(name = "message", required = false) String message) {
        log.info("[POST /api/chat] message(param)='{}', message(body)='{}'",
                message, body != null ? body.message() : null);

        String prompt = StringUtils.hasText(message) ? message
                : (body != null && StringUtils.hasText(body.message()) ? body.message() : null);
        if (!StringUtils.hasText(prompt)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Parametro 'message' obbligatorio");
        }

        var promptSpec = this.chatClient.prompt().user(prompt);
        OllamaChatOptions options = buildOptions(body != null ? body.model() : null);
        if (options != null) promptSpec = promptSpec.options(options);
        return promptSpec.call().content();
    }

    @PostMapping(path = {"", "/"}, consumes = MediaType.TEXT_PLAIN_VALUE, produces = MediaType.TEXT_PLAIN_VALUE)
    public String chatText(
            @RequestBody String prompt,
            @RequestParam(name = "message", required = false) String messageParam,
            @RequestParam(name = "model", required = false) String model) {
        String effective = StringUtils.hasText(messageParam) ? messageParam
                : StringUtils.hasText(prompt) ? prompt : null;
        if (!StringUtils.hasText(effective)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Parametro 'message' obbligatorio");
        }
        var promptSpec = this.chatClient.prompt().user(effective);
        OllamaChatOptions options = buildOptions(model);
        if (options != null) promptSpec = promptSpec.options(options);
        return promptSpec.call().content();
    }

    @GetMapping(path = {"", "/"}, produces = MediaType.TEXT_PLAIN_VALUE)
    public String ping() {
        if (log.isDebugEnabled()) log.debug("[GET /api/chat] ping");
        return "OK";
    }

    // -------------------------------------------------------------------------
    // DTO
    // -------------------------------------------------------------------------

    public record MessageRequest(String message, String model) {}
    public record StreamRequest(RoleMessageRequest[] messages, String model) {}
    public record RoleMessageRequest(String role, String text) {}
    public record DeepChatFile(String name, String type, String data) {}
    public record ImageMessageRequest(String role, String text, List<DeepChatFile> files) {}
    public record ImageRequest(List<ImageMessageRequest> messages, String model) {}
    public record DeepChatResponse(String text) {}
    public record Chunk(String thinking, String text) {}
}