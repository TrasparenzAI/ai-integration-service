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

import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;
public class CapturingToolCallback implements ToolCallback {

    private final ToolCallback delegate;
    private final ToolResultStore store;
    private final String correlationId;

    public CapturingToolCallback(ToolCallback delegate, ToolResultStore store, String correlationId) {
        this.delegate = delegate;
        this.store = store;
        this.correlationId = correlationId;
    }

    @Override
    public ToolDefinition getToolDefinition() { return delegate.getToolDefinition(); }

    @Override
    public ToolMetadata getToolMetadata() { return delegate.getToolMetadata(); }

    @Override
    public String call(String toolInput) {
        String result = delegate.call(toolInput);
        store.capture(correlationId, delegate.getToolDefinition().name(), toolInput, result);
        return result;
    }

    @Override
    public String call(String toolInput, ToolContext toolContext) {
        String result = delegate.call(toolInput, toolContext);
        store.capture(correlationId, delegate.getToolDefinition().name(), toolInput, result);
        return result;
    }
}