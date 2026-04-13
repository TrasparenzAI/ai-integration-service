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
package it.cnr.anac.transparency.ai_integration_service.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
@RequiredArgsConstructor
public class ToolResultStore {

    private final ObjectMapper objectMapper;

    // Mappa correlationId -> lista tool results
    private final ConcurrentHashMap<String, List<Map<String, Object>>> store =
            new ConcurrentHashMap<>();

    public String createCorrelation() {
        String id = UUID.randomUUID().toString();
        store.put(id, new ArrayList<>());
        return id;
    }

    public void capture(String correlationId, String toolName, String input, String result) {
        List<Map<String, Object>> list = store.get(correlationId);
        if (list == null) {
            log.warn("[tool-results] correlationId non trovato: {}", correlationId);
            return;
        }
        try {
            list.add(Map.of(
                    "toolName", toolName,
                    "input",    objectMapper.readTree(input),
                    "result",   parseJsonOrString(result)
            ));
        } catch (Exception e) {
            list.add(Map.of("toolName", toolName, "input", input, "result", result));
        }
    }

    public String buildMarkerAndRemove(String correlationId) {
        List<Map<String, Object>> list = store.remove(correlationId);
        if (list == null || list.isEmpty()) return null;
        try {
            return "<!--TOOL_RESULTS:" + objectMapper.writeValueAsString(list) + "-->";
        } catch (Exception e) {
            log.warn("[tool-results] serializzazione fallita: {}", e.getMessage());
            return null;
        }
    }

    private Object parseJsonOrString(String data) {
        try { return objectMapper.readTree(data); }
        catch (Exception e) { return data; }
    }
}