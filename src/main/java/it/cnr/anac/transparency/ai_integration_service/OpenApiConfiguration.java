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
package it.cnr.anac.transparency.ai_integration_service;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeType;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.security.SecurityScheme;
import io.swagger.v3.oas.annotations.security.SecuritySchemes;
import io.swagger.v3.oas.annotations.servers.Server;
import org.springframework.context.annotation.Configuration;

/**
 * Configurazione dei parametri generali della documentazione tramite OpenAPI.
 */
@Configuration
@OpenAPIDefinition(
    info = @Info(title = "AI Integration Service",
    version = "0.0.1",
    description = "AI Integration Service è il componente che si occupa interagire con Ollama per l'utilizzo " +
            "del AI nella generazione di risposte e contenuti per gli utenti."),
    servers = {
        @Server(url = "/ai-integration-service", description = "AI Integration Service URL"),
        @Server(url = "/", description = "AI Integration Service URL")}
    )
@SecuritySchemes(value = {
    @SecurityScheme(
        name = "bearer_authentication",
        type = SecuritySchemeType.HTTP,
        bearerFormat = "JWT",
        scheme = "bearer")
})
public class OpenApiConfiguration {
    //Empty class
}