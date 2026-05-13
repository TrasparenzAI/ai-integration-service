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

import org.springframework.context.annotation.Configuration;

/**
 * Configurazione dei modelli per gli agenti AI.
 * <p>
 * Fase 1 — infrastruttura base: classe placeholder che centralizza la mappatura
 * agente → modello Ollama. I bean Embabel ({@code AgentPlatform}, registrazione
 * degli agenti, ecc.) verranno aggiunti nelle fasi successive dopo la validazione
 * della compatibilità del framework con Spring Boot 4.x e Spring AI 2.x.
 * <p>
 * Mappatura modelli prevista (dal piano di migrazione):
 * <ul>
 *   <li>{@code OrchestratorAgent} + {@code DatiTrasparenzaAgent} → {@code qwen2.5:14b}</li>
 *   <li>{@code BandiAppaltiAgent} + {@code NormativaAgent}       → {@code qwen3-next:80b}</li>
 *   <li>{@code MultimedialeAgent} (vision)                       → {@code minicpm-v:8b}</li>
 *   <li>{@code MultimedialeAgent} (audio)                        → Whisper (già integrato)</li>
 * </ul>
 */
@Configuration
public class AgentConfig {

    /** Modello leggero per routing e tool calling (OrchestratorAgent, DatiTrasparenzaAgent). */
    public static final String MODEL_ROUTING = "qwen2.5:14b";

    /** Modello pesante per ragionamento su testi normativi (BandiAppaltiAgent, NormativaAgent). */
    public static final String MODEL_DOMAIN  = "qwen3-next:80b-a3b-instruct-q4_K_M";

    /** Modello vision leggero per analisi immagini (MultimedialeAgent). */
    public static final String MODEL_VISION  = "minicpm-v:8b";
}
