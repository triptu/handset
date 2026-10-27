# Handset relay

The relay gives your phone a public MCP address, so claude.ai, ChatGPT and other web apps can reach it. It is a Cloudflare Worker you deploy yourself. Handset opens an outbound WebSocket to it, so the phone needs no public IP or open port. Agents sign in through OAuth, and each sign-in needs your approval on the phone.

The relay runs on its own. A phone, or anything standing in for one, speaks [the phone protocol](PROTOCOL.md), and a fake phone is included so you can test the relay before connecting a real one.

## Deploy your own

Install [Bun](https://bun.sh), then from `relay/`:

```sh
bun install --frozen-lockfile
bun alchemy profile edit --add Cloudflare
bun run deploy --stage production --yes
```

The second command opens Cloudflare sign-in. Skip it when your Alchemy profile already has Cloudflare credentials. The deploy prints a `https://…workers.dev` address. Keep the same stack name and stage for later deploys, so the phone grants already made survive.

Alchemy makes one Worker, one KV namespace for OAuth records, and one Durable Object namespace, where each phone gets its own SQLite object. Idle sockets hibernate, and usage falls under [Cloudflare's free plan limits](https://developers.cloudflare.com/durable-objects/platform/pricing/). A rate limit on registration and sign-in, set in `alchemy.run.ts`, slows abuse before OAuth can write to KV.

Paste the printed address into Handset under Settings, Connections, Relay. Handset makes a key and shows the phone ID. Agents connect to the same address with `/mcp` on the end. Opening the address in a browser shows the same instructions.

## Connect Claude

1. Open Settings, Connectors on [claude.ai](https://claude.ai) and choose Add custom connector. Name it Handset and paste `https://<your-relay>/mcp`.
2. Connect it. On the relay's page, paste the phone ID from Handset.
3. On the phone, read the callback host and the client name. Allow only if you started the sign-in on that host. Compare the six-digit code with the browser, then tap **Allow**.

The browser returns to Claude. Turn Handset on in a chat's connector menu. The same connector works in the Claude apps under the same account, and in a Claude routine. The phone must be online when the routine calls it. Claude's [custom connector guide](https://support.claude.com/en/articles/11175166-get-started-with-custom-connectors-using-remote-mcp) lists which plans allow custom connectors.

The code ties the browser session to the prompt on the phone. A phishing page can relay a real sign-in and show the same code, so a matching code doesn't prove the client is one you trust. The host is what to check.

## Connect ChatGPT

On ChatGPT web, turn on developer mode if your plan offers it. Under Settings, Apps, create a custom MCP app with `https://<your-relay>/mcp` and OAuth. Ask for `offline_access` so ChatGPT can refresh its access. Connect it, paste the phone ID, compare the code, and approve on the phone. Then pick Handset in a chat's app menu. OpenAI's [developer mode guide](https://help.openai.com/en/articles/12584461-developer-mode-and-full-mcp-connectors-in-chatgpt) and [custom MCP setup](https://developers.openai.com/api/docs/guides/custom-mcp-server) cover what each plan allows.

## Privacy

TLS ends at Cloudflare, for the agent's HTTPS connection and the phone's WebSocket alike. Cloudflare and the relay can read calls in transit, so this is not end-to-end encryption. The AI provider receives whatever the phone returns.

The relay never stores a call's content. It holds it in memory only while forwarding. What it does store is OAuth client registrations, encrypted grants and token records, phone grant IDs, client names and approval records. An approval expires after five minutes. Access tokens last an hour and refresh tokens 30 days. A phone grant stays until you remove the agent in Handset, which also stops its refreshed tokens. Request and response bodies are never logged, and Worker observability is off in the deploy. Keep it that way, and don't add frame logging to a proxy.

Permissions, the Send or Pay prompt and Stop stay on the phone. When the phone is offline a call fails at once, and nothing is queued. A call times out after two minutes. If one disconnects or times out, check the phone before retrying, since the action may have completed.

The relay forwards stateless streamable HTTP with JSON answers. It doesn't support SSE or server-started streams.

## Test with the fake phone

Start the local Worker in one terminal and the fake phone in another:

```sh
bun run dev
bun run phone http://127.0.0.1:8787
```

The fake phone prints its phone ID and approves every sign-in, so use it only for tests. It answers one canned `device_status` tool. To keep its identity between runs, set `HANDSET_FAKE_IDENTITY` to a file outside the checkout. The file holds a test private key.

Call it through the MCP Inspector, which opens the sign-in page for you:

```sh
bun inspector --cli http://127.0.0.1:8787/mcp --transport http --method tools/call --tool-name device_status
```

Enter the fake phone's ID, finish the approval, and the command prints the result. For the deployed relay, run the fake phone and the Inspector against its HTTPS address instead.

Claude Code can sign in through the same flow:

```sh
claude mcp add --transport http -s local handset-relay http://127.0.0.1:8787/mcp
claude mcp login handset-relay
claude -p 'Read the phone battery with device_status.' --allowedTools mcp__handset-relay__device_status
claude mcp logout handset-relay
claude mcp remove -s local handset-relay
```

Type-check and run the tests with `bun run check` and `bun test`. The tests run the Worker in Miniflare with real WebSockets and a full OAuth code exchange, and cover an offline phone, replies out of order, revocation, timeouts, reconnects, heartbeats and wrong keys.
