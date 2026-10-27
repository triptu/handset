# Handset relay

A public MCP URL for your phone. Handset opens an outbound WebSocket to a Cloudflare Worker, so the phone needs no public IP or inbound internet port. Claude and ChatGPT sign in through OAuth and ask for permission on the phone.

The relay runs independently of the Android app. Android integration must implement [the phone protocol](PROTOCOL.md). The included fake phone lets you test the relay first.

## Deploy your own

Install [Bun](https://bun.sh), then run these commands from `relay/`:

```sh
bun install --frozen-lockfile
bun alchemy profile edit --add Cloudflare
bun run deploy --stage production --yes
```

The profile command opens Cloudflare sign-in. Skip it if your Alchemy profile already has Cloudflare credentials. Deployment prints a `https://…workers.dev` URL. Keep the same stack name and stage for later deployments so existing phone grants survive.

Registration and authorization each allow 10 attempts per minute per IP through a Cloudflare rate-limit binding, before OAuth can write to KV. Limits are approximate and apply per Cloudflare location. They reduce abuse; they are not a global daily KV budget.

Alchemy provisions one Worker, one OAuth KV namespace, and a SQLite Durable Object namespace. Each connected phone gets its own object. Idle WebSockets use hibernation and heartbeat auto-responses. Usage is subject to [Cloudflare's free plan limits](https://developers.cloudflare.com/durable-objects/platform/pricing/). Active requests and approval polling consume requests and execution time.

Paste the printed HTTPS origin into Handset. The phone generates its key and displays its phone ID. Open the relay URL for setup instructions. Add `/mcp` to the origin when connecting an agent.

Deployment uses Alchemy, including its Cloudflare-backed infrastructure state. It does not deploy through Wrangler. The pinned Alchemy release is the current published v2 beta.

## Connect Claude

1. Open **Settings → Connectors** on [claude.ai](https://claude.ai).
2. Choose **Add custom connector**. Name it Handset and paste `https://<your-relay>/mcp`.
3. Connect the connector. On the relay page, paste the phone ID shown in Handset.
4. Read the callback host and client name on the phone. Allow only if you started the connection on that host. Compare the six-digit code with the browser, then tap **Allow**.

The code binds the browser session to the phone prompt. A phishing page can relay a real flow and display the same code. A matching code does not prove the requesting client is trustworthy.

The browser returns to Claude. Enable Handset in the chat's connector menu. The same connector is available in the Claude mobile app under the same account. Add it on the web if your mobile version does not expose custom connector setup. Use it in a Claude routine where that routine supports connectors. The phone must be online when the routine calls it.

Claude's web callback is `https://claude.ai/api/mcp/auth_callback`. The OAuth provider validates each client's registered callback rather than hard-coding this address. See [Claude's custom connector guide](https://support.claude.com/en/articles/11175166-get-started-with-custom-connectors-using-remote-mcp) for plan and workspace restrictions.

## Connect ChatGPT

On ChatGPT web, enable developer mode if your account or workspace offers it. Under **Settings → Apps**, create a custom MCP app with `https://<your-relay>/mcp` and OAuth authentication. Some accounts expose this through **Plugins → Add custom MCP server**. Connect the app, paste the phone ID, compare the code, and approve on the phone. Ask for `offline_access` when configuring OAuth so the client can refresh its access.

Select Handset in the chat's app menu. Availability depends on your plan and workspace settings. See [OpenAI's developer mode guide](https://help.openai.com/en/articles/12584461-developer-mode-and-full-mcp-connectors-in-chatgpt) and [custom MCP setup](https://developers.openai.com/api/docs/guides/custom-mcp-server). These cloud clients require the public HTTPS URL, even when the phone and browser share Wi-Fi.

## Privacy

TLS ends at Cloudflare for both the client HTTPS connection and the phone WSS connection. The relay and Cloudflare can read requests and responses in transit. This is not end-to-end encryption. The AI provider also receives whatever the phone returns to its tools.

The relay never stores MCP request or response content. It keeps that content in memory only while forwarding a call. It stores OAuth client registrations, encrypted OAuth grant properties and token records, phone grant IDs, client names, and approval metadata. Approval transactions expire after five minutes. Access tokens last one hour and refresh tokens last 30 days. Active phone grants remain until revoked.

Request and response bodies are never logged. Worker observability is disabled in the deployment. Cloudflare still handles connection metadata under its own policies. Do not enable body logging in a proxy or add protocol frame logging. The fake phone identity file contains a test private key and belongs outside the checkout.

Remove an agent in Handset to revoke its grant immediately. Revocation also blocks refreshed OAuth tokens for that grant. Permissions and consent for phone actions remain on the phone. If the phone is offline, requests fail with a clear error instead of being queued for later execution.

The relay forwards stateless Streamable HTTP with JSON responses. It does not support SSE or server-initiated MCP streams. Calls have a 120-second timeout. If a call disconnects or times out, check the phone before retrying because the action may already have completed.

## Test before connecting a real phone

Start the local Worker in one terminal and the fake phone in another:

```sh
bun run dev
```

```sh
bun run phone http://127.0.0.1:8787
```

The fake phone prints its phone ID and automatically approves every request. Use it only for tests. It exposes one canned `device_status` tool with battery 73% and charging false. To keep its identity between runs, set `HANDSET_FAKE_IDENTITY` to a file outside the checkout. The script writes that file with mode 0600.

Run the Inspector CLI in a terminal:

```sh
bun inspector --cli http://127.0.0.1:8787/mcp --transport http \
  --method tools/call --tool-name device_status
```

Open the authorization URL it prints, enter the fake phone's ID, and complete approval. The command prints the tool result after the browser callback. For the public test, run the fake phone and Inspector with the deployed HTTPS origin instead.

Claude Code can authenticate through the same flow:

```sh
claude mcp add --transport http -s local handset-relay http://127.0.0.1:8787/mcp
claude mcp login handset-relay
claude -p 'Read the phone battery with device_status.' --allowedTools mcp__handset-relay__device_status
claude mcp logout handset-relay
claude mcp remove -s local handset-relay
```

Run the checks:

```sh
bun run check
bun test
```

Tests run the bundled Worker in Miniflare, Cloudflare's local runtime, with real WebSockets and a full OAuth code exchange. They cover an offline phone, concurrent replies in reverse order, revocation including refreshed tokens, timeout cancellation, discovery, wrong-key authentication, reconnects, heartbeats, and in-flight disconnects.

Cloudflare recommends `createMcpHandler` for servers that implement MCP tools and marks `McpAgent` deprecated. This Worker uses neither because it forwards requests to the phone's MCP handler. See [Cloudflare's transport documentation](https://developers.cloudflare.com/agents/model-context-protocol/protocol/transport/).
