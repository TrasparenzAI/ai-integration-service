/*
 * Copyright (C) 2026 Consiglio Nazionale delle Ricerche
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
package it.cnr.anac.transparency.ai_integration_service.service;

import it.cnr.anac.transparency.ai_integration_service.config.TextToSpeechProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.cloud.context.config.annotation.RefreshScope;
import org.springframework.http.MediaType;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;

import java.util.Base64;
import java.util.Map;

@Service
@Slf4j
@RefreshScope
public class TtsService {

    private final WebClient ttsClient;
    private final TextToSpeechProperties properties;

    public boolean isEnable() {
        return properties.getEnable();
    }

    public TtsService(WebClient.Builder builder,
                      @Value("${kokoro.base-url:http://kokoro:8880}") String baseUrl, TextToSpeechProperties properties) {
        this.properties = properties;
        this.ttsClient = builder
                .baseUrl(baseUrl)
                .clientConnector(new ReactorClientHttpConnector(
                        HttpClient.create().responseTimeout(properties.getResponseTimeout())
                ))
                .exchangeStrategies(ExchangeStrategies.builder()
                        .codecs(configurer -> configurer
                                .defaultCodecs()
                                .maxInMemorySize(-1))
                        .build())
                .build();
    }

    public Mono<String> synthesizeBase64(String text) {
        String markdownToNaturalText = markdownToNaturalText(text);
        log.debug("Try to speech text: {}", text);
        log.debug("Trasformed to: {}", markdownToNaturalText);
        return ttsClient.post()
                .uri("/v1/audio/speech")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of(
                        "model",  properties.getModel(),
                        "input", markdownToNaturalText,
                        "voice",  properties.getVoice(),
                        "response_format", properties.getResponseFormat()
                ))
                .retrieve()
                .bodyToMono(byte[].class)
                .map(bytes -> Base64.getEncoder().encodeToString(bytes))
                .onErrorResume(e -> {
                    log.warn("Kokoro TTS fallito: {}", e.getMessage());
                    return Mono.empty();   // audio facoltativo, non blocca
                });
    }

    private String markdownToNaturalText(String markdown) {
        if (markdown == null) return "";
        return markdown
                // normalizza line endings
                .replace("\r\n", "\n")
                .replace("\r", "\n")
                .replaceAll("#{1,6}\\s+", "")
                // titoli (con eventuale spazio iniziale)
                .replaceAll("(?m)^\\s*#{1,6}\\s+(.+)$", "$1. ")
                // grassetto+corsivo combinati (***testo***)
                .replaceAll("\\*{3}([^*]+)\\*{3}", "$1")
                // grassetto (**testo** o __testo__)
                .replaceAll("\\*{2}([^*]+)\\*{2}", "$1")
                .replaceAll("_{2}([^_]+)_{2}", "$1")
                // corsivo (*testo* o _testo_) — dopo il grassetto!
                .replaceAll("\\*([^*\\n]+)\\*", "$1")
                .replaceAll("_([^_\\n]+)_", "$1")
                // blocchi di codice
                .replaceAll("(?s)```.*?```", "")
                .replaceAll("`([^`]+)`", "$1")
                // link e immagini
                .replaceAll("!\\[[^\\]]*\\]\\([^)]+\\)", "")
                .replaceAll("\\[([^\\]]+)\\]\\([^)]+\\)", "$1")
                // liste non ordinate
                .replaceAll("(?m)^\\s*[-*+]\\s+(.+)$", "$1, ")
                // liste ordinate
                .replaceAll("(?m)^\\s*\\d+\\.\\s+(.+)$", "$1, ")
                // blockquote
                .replaceAll("(?m)^\\s*>+\\s*", "")
                // separatori orizzontali
                .replaceAll("(?m)^\\s*[-*_]{3,}\\s*$", ". ")
                // HTML tags
                .replaceAll("<[^>]+>", "")
                // paragrafi → pausa
                .replaceAll("\\n{2,}", ". ")
                .replaceAll("\\n", ", ")
                // pulizia punteggiatura ridondante
                .replaceAll(",\\s*,", ",")
                .replaceAll(",\\s*\\.", ".")
                .replaceAll("\\.\\s*\\.", ".")
                .replaceAll("\\.\\s*,", ".")
                .replaceAll("([.!?])\\s*,", "$1")
                .replaceAll("\\s{2,}", " ")
                .trim();
    }
}
