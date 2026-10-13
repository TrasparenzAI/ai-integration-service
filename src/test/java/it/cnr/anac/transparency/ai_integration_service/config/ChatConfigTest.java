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

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ChatConfigTest {

    private static final String BASE = "Prompt di base.";

    private static final String DVNS = "Usa list_datasets prima di query_dataset.";

    @Test
    void dvnsRuleAddedWhenActive() {
        assertThat(ChatConfig.systemPrompt(BASE, true, DVNS)).isEqualTo(BASE + "\n" + DVNS);
    }

    @Test
    void dvnsRuleOmittedWhenNotActive() {
        assertThat(ChatConfig.systemPrompt(BASE, false, DVNS)).isEqualTo(BASE);
    }

    @Test
    void blankDvnsRuleIgnored() {
        assertThat(ChatConfig.systemPrompt(BASE, true, "")).isEqualTo(BASE);
    }

}
