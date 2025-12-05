# AI Integration Service del progetto TrasparenzAI
## AI Integration Service - Integrazione con Ollama dei componenti TransparenzAI

[![Supported JVM Versions](https://img.shields.io/badge/JVM-21-brightgreen.svg?style=for-the-badge&logo=Java)](https://openjdk.java.net/install/)

AI Integration Service è parte della suite di servizi per la verifica delle informazioni sulla
Trasparenza dei siti web delle Pubbliche amministrazioni italiane.

## AI Integration Service

AI Integration Service è il componente che si occupa interagire con Ollama per l'utilizzo
del AI nella generazione di risposte e contenuti per gli utenti.

## 🔐 Autenticazione: OAuth2 Resource Server con JWT

Questo servizio può funzionare come Resource Server OAuth2 validando token Bearer JWT secondo la configurazione nativa Spring Boot.

Per default l'autenticazione è ABILITATA. Per disabilitarla e rendere accessibili senza autenticazione tutte
le API  sotto `/api/**` puoi disabilitare resource Server nel file `application.properties` con `security.oauth2.resourceserver.enabled=true`.

Per configurare l'autenticazione:

1) Configura la validazione JWT usando uno dei due metodi (scegline uno):

- Issuer OIDC (consigliato, discovery automatico):

```
spring.security.oauth2.resourceserver.jwt.issuer-uri=https://dica33.ba.cnr.it/keycloak/realms/trasparenzai
```

- Oppure JWK Set URI diretto:

```
spring.security.oauth2.resourceserver.jwt.jwk-set-uri=https://https://dica33.ba.cnr.it/keycloak/realms/trasparenzai/protocol/openid-connect/certs
```

2) Effettua le chiamate alle API includendo l'header Authorization:

```
Authorization: Bearer <jwt>
```

Note:
- Sono sempre consentiti senza autenticazione: risorse statiche, `GET /actuator/health`, `GET /actuator/info` e le richieste `OPTIONS` (per CORS).
- CSRF è disabilitato per le API stateless. Il CORS è abilitato in modo permissivo; adegua in produzione (origini, metodi, header) secondo le tue policy.

## ✅ Configurare un MCP Server con Ollama (Spring AI)

Questo progetto include già lo starter Spring AI per agire come client MCP:

- `spring-ai-starter-mcp-client`
- `spring-ai-starter-model-ollama`

Per indicare all'applicazione di usare uno o più MCP Server (HTTP/SSE o STDIO) basta
configurare le proprietà in `src/main/resources/application.properties`.

1) Abilita MCP:

```
spring.ai.mcp.client.enabled=true
```

2) Aggiungi uno o più server MCP. Esempi:

- Via HTTP/SSE (server esterno):

```
# id: local_mcp
# Simple configuration using default /sse endpoint
spring.ai.mcp.client.streamable-http.connections.local_mcp.url: http://localhost:8081

```


Suggerimento: puoi definire quanti server vuoi con ID diversi (`tools`, `public_site_mcp_server`, `results_mcp_server`, …).

3) Configura Ollama in locale (già presente):

```
spring.ai.ollama.base-url=http://localhost:11434
spring.ai.ollama.chat.options.model=llama3
```

Note:
- Gli strumenti esposti dai server MCP vengono registrati automaticamente e resi disponibili
  al ChatClient di Spring AI. Se il modello supporta il tool/function calling, Spring AI li utilizzerà
  quando pertinente durante la conversazione.
- Alcuni modelli Ollama hanno migliore supporto al tool calling (es. famiglia Llama 3.1). Verifica la
  documentazione del modello per risultati ottimali.

### Verifica rapida

1. Avvia i tuoi MCP server (HTTP/STDIO) come da configurazione.
2. Avvia questa app Spring Boot.
3. Ottieni un token JWT Valido

```
export CLIENT_ID=il_tuo_client_id
export CLIENT_SECRET=il_tuo_client_secret

ACCESS_TOKEN=$(curl 'https://dica33.ba.cnr.it/keycloak/realms/trasparenzai/protocol/openid-connect/token' -H 'accept: application/json, text/plain, */*' --data "grant_type=client_credentials&client_id=$CLIENT_ID&client_secret=$CLIENT_SECRET"| jq -r '.access_token')
```

4. Chiama l'endpoint SSE per lo streaming token:

```
GET -H "Authorization: Bearer $ACCESS_TOKEN" http://localhost:8080/v1/chat/?message=Quali strumenti MCP sono disponibili?
```

Controlla i log: vedrai la registrazione delle connessioni MCP e, durante l'uso,
il modello potrà invocare gli strumenti MCP se rilevanti.

## 👏 Come Contribuire

E' possibile contribuire a questo progetto utilizzando le modalità standard della comunità opensource
(issue + pull request) e siamo grati alla comunità per ogni contribuito a correggere bug e miglioramenti.

## 📄 Licenza

AI Integration Scheduler Service è concesso in licenza GNU AFFERO GENERAL PUBLIC LICENSE, come si trova nel file
[LICENSE][l].

[l]: https://github.com/trasparenzai/public-sites-service/blob/master/LICENSE
