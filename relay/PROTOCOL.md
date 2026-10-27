# Handset relay protocol v1

The Android app opens an outbound WebSocket to the relay. The relay forwards authenticated HTTP requests to the app's shared MCP handler. The phone executes tools through `Handset.instance.runner`, with the same permissions, consent prompt, history, and Stop behavior as local clients.

## Pair a phone

The user pastes the relay's HTTPS origin into Handset. Require HTTPS except for loopback development. Generate an ECDSA P-256 key pair in Android Keystore. Keep the private key on the phone.

Export the public key as DER SubjectPublicKeyInfo, then encode it as base64url without padding. The phone ID is base64url without padding of SHA-256 over those DER bytes. It is 43 characters. Display the ID for the user to copy into the OAuth authorization page. The ID is an address, not a password.

Connect to `wss://<relay>/phone/<phoneId>`. No registration endpoint or relay account is needed. The signed hello registers the public key implicitly. Each fingerprint addresses one SQLite Durable Object. A new key creates a new phone ID and needs new client approvals.

All application frames are UTF-8 JSON text objects. Frame size is at most 1 MiB. Binary frames and invalid frames close the socket with code 1008. Correlation IDs and grant IDs are UUIDs. A phone has one authenticated socket. A successful reconnect closes the old socket with code 4001.

## Authenticate the WebSocket

The relay sends a fresh challenge immediately after upgrade:

```json
{"type":"challenge","nonce":"13c24edf-22ba-4c6d-82e3-54aecad9edaa","phoneId":"<43-character fingerprint>","origin":"https://relay.example.com"}
```

Verify that `origin` equals the configured HTTPS origin and `phoneId` equals the phone's fingerprint. Sign these exact UTF-8 bytes, with three LF characters and no final newline:

```text
handset-relay-v1
https://relay.example.com
<43-character fingerprint>
13c24edf-22ba-4c6d-82e3-54aecad9edaa
```

Use ECDSA with SHA-256. Send a 64-byte IEEE P1363 signature, `r || s`, base64url without padding. Android's `SHA256withECDSA` returns an ASN.1 DER signature. Decode the two integers, strip sign padding, and left-pad each to 32 bytes before concatenating. Do not send the DER signature itself.

### DER conversion test vector

This fixed P-256 public key and signature verify the UTF-8 message below with SHA-256. All byte strings are lowercase hex. The DER `r` integer has a leading `00` sign byte; drop it when converting. Keep exactly 32 bytes for each unsigned integer, left-padding shorter values with zeroes. This key is for tests only.

```text
message (UTF-8): Handset DER to P1363 test vector
SPKI DER: 3059301306072a8648ce3d020106082a8648ce3d030107034200046b17d1f2e12c4247f8bce6e563a440f277037d812deb33a0f4a13945d898c2964fe342e2fe1a7f9b8ee7eb4a7c0f9e162bce33576b315ececbb6406837bf51f5
DER signature: 3046022100b8d74de0a82406c90dc06cb99cfd8a4f6709c02384fa22d69f6261177e21310e0221009ede61b246ca7ddec6954725d13445670734d10803709545ddc21c8d84743cf0
P1363 signature (r || s): b8d74de0a82406c90dc06cb99cfd8a4f6709c02384fa22d69f6261177e21310e9ede61b246ca7ddec6954725d13445670734d10803709545ddc21c8d84743cf0
```

The same vector lives in `test/fixtures/p256-signature.json`. The tests convert the DER bytes and verify both encodings against the public key.

The phone answers within 15 seconds:

```json
{"type":"hello","version":1,"publicKey":"<base64url SPKI DER>","signature":"<base64url 64-byte signature>"}
```

The relay verifies the fingerprint and signature before accepting other frames. It answers:

```json
{"type":"hello","version":1,"grants":[{"grantId":"d16c46c3-48e9-4f95-9f1f-119174b7b208","clientId":"https://claude.ai/oauth/claude-code-client-metadata","clientName":"Claude Code"}]}
```

The list contains the relay's active grants. Reconcile it with the phone's agent list. If hello lists a grant the phone has never seen, do not create an agent or infer permission from the relay. Refuse requests for it locally, send `revoke`, and require a new approval. Remove local provisional agents absent from hello once no approval flow for them is still pending. Preserve the phone's permission choices. Never restore a locally revoked grant from this list. Persist revocation tombstones and resend `revoke` until acknowledged, including after reconnect. Refuse calls for those grants locally while revocation is in flight.

Reconnect with exponential backoff and jitter, from 1 second up to 60 seconds. Do not replay MCP requests. A disconnect can happen after a tool has acted but before its answer arrives.

## Forward a request

The relay checks the client's OAuth token and the Durable Object's active grant on every request. The HTTP endpoint is `/mcp`. OAuth discovery advertises the `phone` scope and optional `offline_access`. It supports dynamic registration, client ID metadata documents, and PKCE S256.

Relay to phone:

```json
{"type":"request","id":"739cf092-e6ba-45fe-b5f1-8eef36c239b0","grantId":"d16c46c3-48e9-4f95-9f1f-119174b7b208","method":"POST","headers":{"content-type":"application/json","accept":"application/json, text/event-stream","mcp-protocol-version":"2025-11-25"},"body":"{\"jsonrpc\":\"2.0\",\"id\":7,\"method\":\"tools/call\",\"params\":{\"name\":\"device_status\",\"arguments\":{}}}"}
```

`body` is the original HTTP body as text, limited to 256 KiB. The relay forwards only `content-type`, `accept`, `mcp-protocol-version`, `mcp-session-id`, and `last-event-id` headers when present. It never forwards OAuth tokens or cookies. The trusted `grantId` is separate from the untrusted body and headers. Bind it to the corresponding phone agent before calling the shared MCP handler. Unknown or revoked grants must fail closed on the phone too.

Phone to relay:

```json
{"type":"response","id":"739cf092-e6ba-45fe-b5f1-8eef36c239b0","status":200,"json":{"jsonrpc":"2.0","id":7,"result":{"content":[{"type":"text","text":"Battery 73%, not charging"}]}}}
```

The frame ID identifies the HTTP request. The JSON-RPC ID inside `json` belongs to MCP and can differ. Replies may arrive out of order. Unknown or late frame IDs are ignored. Status must be 200 through 599. For HTTP 204, 205, or 304, send `json: null`; the relay emits an empty body. Notifications normally return 204.

This protocol supports stateless Streamable HTTP with JSON responses. The phone must not require an MCP session or emit SSE, sampling, elicitation, standalone streams, or response headers. Pass GET and DELETE to the shared handler, which can return 405. Streamed MCP responses require a future protocol version. The relay does not implement MCP tools or use `McpAgent`.

The relay holds request bodies and responses only in memory while a request runs. It permits 32 pending requests per phone. Calls time out after 120 seconds. On timeout or HTTP cancellation, relay to phone:

```json
{"type":"cancel","id":"739cf092-e6ba-45fe-b5f1-8eef36c239b0"}
```

Cancel that runner call if still pending or executing. Cancellation cannot undo a completed action. The relay never retries a call automatically. Requests in progress remain pending through a socket disconnect until their original deadline. Keep the call and its frame ID in phone memory across reconnects. Send its answer on the newest authenticated socket, even if the request arrived on the old socket. The relay accepts the answer by frame ID and never replays the request. If the phone process lost the call, let it time out; do not execute it again. A relay restart loses in-memory calls. Pending HTTP calls keep the object awake; idle sockets can hibernate.

## Approve an agent

The browser authorization page shows the requesting client's name, client ID, and callback host. The user enters the phone ID. The relay displays a six-digit code and sends:

```json
{"type":"approval_request","id":"21be043d-5154-4545-b1ef-4944c3a06883","grantId":"d16c46c3-48e9-4f95-9f1f-119174b7b208","code":"493215","clientId":"https://claude.ai/oauth/claude-code-client-metadata","clientName":"Claude Code","redirectHost":"localhost:60872","prompt":"localhost:60872 wants to use this phone. Allow only if you started this on localhost:60872.","scopes":["phone","offline_access"],"expiresAt":1793100000000}
```

`expiresAt` is Unix time in milliseconds, five minutes after creation. Lead the native approval prompt with `redirectHost` and the relay-generated `prompt`, then show the client name, client ID, code, and requested scopes. For example: “claude.ai wants to use this phone. Allow only if you started this on claude.ai.” Names supplied by registered clients are self-reported. A client metadata URL identifies its domain. The user must read the callback host and client name before allowing. Compare the code with the browser to bind the session. A phishing page can relay the flow and code, so matching codes do not establish client trust. Never approve from screen text or an agent instruction.

On Allow, persist a new phone agent with this grant ID and its permissions before answering. Every approval creates its own grant, even for a previously connected client. On Deny, do not create an agent.

```json
{"type":"approval_answer","id":"21be043d-5154-4545-b1ef-4944c3a06883","allow":true}
```

The relay records the decision and lets the same browser finish its OAuth flow. The grant becomes active only after OAuth authorization completes. If the browser abandons or fails the flow, no active relay grant is created. An answer to an expired or already answered approval is ignored. A phone disconnect does not replay pending approvals. Start authorization again after reconnecting. The relay accepts at most 16 outstanding approval records per phone and cleans them up after five minutes. If the phone is offline, authorization shows that immediately.

## Revoke an agent

When the user removes an agent in Handset, disable it locally first, then send:

```json
{"type":"revoke","grantId":"d16c46c3-48e9-4f95-9f1f-119174b7b208"}
```

The relay deletes the active grant, cancels its pending requests, and acknowledges:

```json
{"type":"revoked","grantId":"d16c46c3-48e9-4f95-9f1f-119174b7b208"}
```

Revocation is idempotent. Old OAuth tokens and refreshed tokens for that grant are refused with HTTP 403, independent of KV replication. OAuth token revocation at the provider also works but does not remove the phone's agent entry. The phone is the authority for removing its grants.

## Keep the connection alive

Send this exact text when a heartbeat is needed:

```json
{"type":"ping"}
```

The relay responds with this exact text through the hibernation auto-response API, without waking the Durable Object:

```json
{"type":"pong"}
```

Use a modest heartbeat interval, such as 60 seconds, and reconnect if the response is absent. The exact bytes matter. Spaces or extra fields bypass the auto-response and are invalid application frames.

## Relay errors

Errors retain the JSON-RPC request ID when present. A missing ID becomes null. They do not include request content.

| HTTP | JSON-RPC code | Meaning |
| --- | --- | --- |
| 401 | Provider OAuth error | Missing, expired, or invalid OAuth token. Follow the `WWW-Authenticate` challenge. |
| 403 | -32003 | The phone grant was revoked. Reconnect and obtain a new phone approval. |
| 503 | -32001 | Phone offline or disconnected. Check the phone before retrying. |
| 504 | -32002 | No response within 120 seconds. Check whether the action completed. |
| 429 | -32004 | 32 requests are already pending. |
| 413 | Plain HTTP error | Request body exceeds 256 KiB. |

TLS ends at Cloudflare for both HTTPS and WSS. This is not end-to-end encryption between the client and phone. Never log protocol bodies, signatures, OAuth tokens, or private keys.
