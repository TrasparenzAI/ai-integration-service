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
package it.cnr.anac.transparency.ai_integration_service.clients;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.multipart.MultipartFile;

/**
 * Client Feign per il servizio di trascrizione audio Whisper.
 *
 * <p>Utilizza l'endpoint {@code /asr} del container Whisper
 * ({@code onerahmet/openai-whisper-asr-webservice}) per convertire
 * file audio in testo.
 *
 * <p>Configurazione richiesta in {@code application.yml}:
 * <pre>{@code
 * whisper:
 *   base-url: http://whisper:9000
 * }</pre>
 */
@FeignClient(name = "whisper", url = "${whisper.base-url}")
public interface WhisperClient {

    /**
     * Trascrive un file audio in testo.
     *
     * @param audioFile file audio come {@link MultipartFile}
     * @param language  codice lingua (es. {@code "it"}, {@code "en"})
     * @param output    formato di output: {@code "txt"} per testo puro,
     *                  {@code "json"} per JSON con metadati
     * @return testo trascritto
     */
    @PostMapping(
            value = "/asr",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE,
            produces = MediaType.TEXT_PLAIN_VALUE
    )
    String transcribe(
            @RequestPart("audio_file") MultipartFile audioFile,
            @RequestParam("language") String language,
            @RequestParam("output") String output
    );
}