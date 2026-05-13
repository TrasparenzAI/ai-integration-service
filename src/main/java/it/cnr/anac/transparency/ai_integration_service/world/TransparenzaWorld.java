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
package it.cnr.anac.transparency.ai_integration_service.world;

import lombok.Builder;
import lombok.Data;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.content.Media;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Contesto condiviso tra tutti gli agenti per una singola richiesta.
 * <p>
 * Rappresenta il "World" del framework Embabel: lo stato che viene passato
 * tra gli agenti durante il ciclo di pianificazione ed esecuzione.
 * Ogni agente legge dal World i dati di cui ha bisogno e scrive il proprio output.
 * <p>
 * Fase 1 — infrastruttura base: questa classe è un POJO puro, senza annotazioni
 * Embabel. Le annotazioni {@code @Agent} e {@code @Action} verranno aggiunte
 * nelle fasi successive, dopo la validazione della compatibilità del framework.
 */
@Data
@Builder
public class TransparenzaWorld {

    // ── Input dalla richiesta HTTP ────────────────────────────────────────────

    /** Testo del messaggio dell'utente. */
    private String userQuery;

    /** Storia della conversazione (messaggi precedenti). */
    private List<Message> conversationHistory;

    /** Immagini allegate alla richiesta corrente. */
    private List<Media> attachedImages;

    // ── Prodotto dall'OrchestratorAgent ───────────────────────────────────────

    /** Dominio classificato: "bandi" | "normativa" | "multimedia" | "dati". */
    private String detectedDomain;

    /** Ordine di esecuzione pianificato dall'orchestratore. */
    private List<String> agentPlan;

    // ── Prodotto dal MultimedialeAgent ────────────────────────────────────────

    /** Testo trascritto da file audio via Whisper. */
    private String audioTranscription;

    /** Descrizione/analisi di immagini allegata. */
    private String imageAnalysis;

    // ── Prodotto dal DatiTrasparenzaAgent ─────────────────────────────────────

    /** Risultati dei tool MCP invocati (nome tool + risultato serializzato). */
    private List<Map<String, Object>> mcpResults;

    // ── Prodotto dall'agente di dominio principale ────────────────────────────

    /** Analisi prodotta dall'agente specializzato (bandi, normativa, dati). */
    private String domainAnalysis;

    // ── Prodotto dall'OrchestratorAgent al termine ────────────────────────────

    /** Risposta finale composta e pronta per essere inviata al client. */
    private String finalResponse;

    // ── Metadati ──────────────────────────────────────────────────────────────

    /** Identificatore di correlazione per tracciare la richiesta end-to-end. */
    private String correlationId;

    /** Timestamp di inizio elaborazione. */
    @Builder.Default
    private Instant startTime = Instant.now();
}
