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

import io.modelcontextprotocol.client.McpSyncClient;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.context.config.annotation.RefreshScope;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Configuration
@RefreshScope
public class ChatConfig {

    @Value("${ai.systemPrompt}")
    String SYSTEM_PROMPT;

    @Bean
    @RefreshScope
    public SyncMcpToolCallbackProvider mcpToolCallbackProvider(List<McpSyncClient> mcpClients) {
        return SyncMcpToolCallbackProvider.builder()
                .mcpClients(mcpClients)
                .build();
    }

    @Bean
    @RefreshScope
    ChatClient chatClient(ChatClient.Builder chatClientBuilder) {
        // Niente defaultToolCallbacks qui — li passiamo per request
        return chatClientBuilder
                .defaultSystem(SYSTEM_PROMPT)
                .build();
    }
}
