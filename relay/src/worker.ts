import { OAuthProvider, AuthorizationError } from "@cloudflare/workers-oauth-provider";
import type { OAuthHelpers } from "@cloudflare/workers-oauth-provider";
import { z } from "zod";
import { Phone, approvalInput } from "./phone";
import { MAX_BODY_BYTES, PHONE_ID } from "./protocol";
export { Phone };

export interface Env {
  PHONES: DurableObjectNamespace<Phone>;
  OAUTH_KV: KVNamespace;
  AUTH_LIMIT: RateLimit;
  OAUTH_PROVIDER: OAuthHelpers;
  REQUEST_TIMEOUT_MS?: string;
}
const propsSchema = z.object({ phoneId: z.string().regex(PHONE_ID), grantId: z.string().uuid() });
const ticketSchema = z.object({
  clientId: z.string(),
  clientName: z.string(),
  redirectHost: z.string(),
  scopes: z.array(z.string()),
  approval: approvalInput.extend({ phoneId: z.string() }).nullable(),
});
const approvalStatus = z.object({
  state: z.enum(["pending", "approved", "denied"]),
  online: z.boolean(),
});
const escape = (s: string) =>
  s
    .replaceAll("&", "&amp;")
    .replaceAll("<", "&lt;")
    .replaceAll('"', "&quot;")
    .replaceAll("'", "&#39;");
function page(content: string, headers = new Headers(), script = ""): Response {
  const nonce = crypto.randomUUID();
  headers.set("Content-Type", "text/html; charset=utf-8");
  headers.set("Cache-Control", "no-store");
  headers.set("Referrer-Policy", "same-origin");
  headers.set("X-Frame-Options", "DENY");
  headers.set(
    "Content-Security-Policy",
    `default-src 'none'; style-src 'nonce-${nonce}'; script-src 'nonce-${nonce}'; connect-src 'self'; frame-ancestors 'none'; base-uri 'none'`,
  );
  return new Response(
    `<!doctype html><html lang="en"><meta charset="utf-8"><meta name="viewport" content="width=device-width"><title>Handset relay</title><style nonce="${nonce}">body{font:18px/1.6 system-ui;max-width:36em;margin:12vh auto;padding:24px;color:#202820;background:#f6f5f0}input,button{font:inherit;padding:10px;max-width:100%;box-sizing:border-box}input{width:100%}code{overflow-wrap:anywhere}button{margin-top:20px}small{display:block}h1{line-height:1.2}</style><h1>Handset relay</h1>${content}${script ? `<script nonce="${nonce}">${script}</script>` : ""}</html>`,
    { headers },
  );
}
function phone(env: Env, id: string): DurableObjectStub<Phone> {
  return env.PHONES.get(env.PHONES.idFromName(id));
}
function internal(path: string, init?: RequestInit): Request {
  return new Request(`https://phone.internal${path}`, init);
}
async function ticket(env: Env, handle: string) {
  if (handle.length > 1024) throw new Error("Invalid approval");
  return ticketSchema.parse(await env.OAUTH_KV.get(`handset:consent:${handle}`, "json"));
}
async function defaultFetch(request: Request, env: Env): Promise<Response> {
  const url = new URL(request.url);
  if (url.pathname === "/")
    return page(
      `<p>Connect your phone to <code>${escape(url.origin)}</code> in Handset. The app creates a key and opens an outbound connection.</p><p>Add <code>${escape(url.origin)}/mcp</code> to your agent. Keep the phone ID from Handset for authorization.</p><p>Each connection needs your approval on the phone. Requests pass through Cloudflare. The relay stores access grants, never phone content.</p>`,
    );
  if (url.pathname === "/authorize" && request.method === "GET") {
    const auth = await env.OAUTH_PROVIDER.parseAuthRequest(request);
    if (auth.codeChallengeMethod !== "S256")
      return page("<p>This client must use OAuth PKCE S256.</p>");
    const description = await env.OAUTH_PROVIDER.describeConsent(auth);
    const client = await env.OAUTH_PROVIDER.lookupClient(auth.clientId);
    const consent = await env.OAUTH_PROVIDER.beginConsent(auth);
    const clientName = client?.clientName ?? "Unknown client";
    await env.OAUTH_KV.put(
      `handset:consent:${consent.handle}`,
      JSON.stringify({
        clientId: auth.clientId,
        clientName,
        redirectHost: new URL(auth.redirectUri).host,
        scopes: auth.scope,
        approval: null,
      }),
      { expirationTtl: 300 },
    );
    return page(
      `<h2>Connect ${escape(clientName)}</h2><p>Callback: <code>${escape(new URL(auth.redirectUri).host)}</code></p><p>Client ID: <code>${escape(auth.clientId)}</code></p><p>${description.clientDomain ? `Verified client domain: ${escape(description.clientDomain)}` : "The client name is self-reported."} ${description.redirectIsLoopback ? "The callback goes to a program on this computer." : ""}</p><p>Access: ${escape(auth.scope.join(", "))}</p><form method="post" action="/authorize"><input type="hidden" name="handle" value="${escape(consent.handle)}"><label>Phone ID from Handset<input name="phoneId" required minlength="43" maxlength="43" autocomplete="off"></label><button>Ask the phone</button></form>`,
      consent.headers,
    );
  }
  if (["/authorize", "/status", "/finish"].includes(url.pathname) && request.method === "POST") {
    if (request.headers.get("Origin") !== url.origin)
      return new Response("Same-origin form required", { status: 403 });
    const form = await request.formData();
    const handle = z.string().parse(form.get("handle"));
    const saved = await ticket(env, handle);
    if (url.pathname === "/authorize") {
      if (saved.approval) return new Response("Already requested", { status: 409 });
      const phoneId = z.string().regex(PHONE_ID).parse(form.get("phoneId"));
      const approval = {
        id: crypto.randomUUID(),
        grantId: crypto.randomUUID(),
        code: String(crypto.getRandomValues(new Uint32Array(1))[0] ?? 0)
          .slice(-6)
          .padStart(6, "0"),
        clientId: saved.clientId,
        clientName: saved.clientName,
        redirectHost: saved.redirectHost,
        prompt: `${saved.redirectHost} wants to use this phone. Allow only if you started this on ${saved.redirectHost}.`,
        scopes: saved.scopes,
        expiresAt: Date.now() + 300_000,
      };
      const result = await phone(env, phoneId).fetch(
        internal("/approval", { method: "POST", body: JSON.stringify(approval) }),
      );
      if (!result.ok)
        return page(
          `<p>${result.status === 503 ? "Phone is offline. Open Handset and reconnect, then start authorization again." : "The phone cannot accept another approval. Try again later."}</p>`,
        );
      await env.OAUTH_KV.put(
        `handset:consent:${handle}`,
        JSON.stringify({ ...saved, approval: { ...approval, phoneId } }),
        { expirationTtl: 300 },
      );
      return page(
        `<h2>${escape(saved.redirectHost)} wants to use this phone</h2><p>Allow only if you started this on ${escape(saved.redirectHost)}. Client: ${escape(saved.clientName)}.</p><p>Compare this code with the one in Handset, then tap Allow on the phone.</p><h2>${approval.code}</h2><p id="state" role="status">Waiting for the phone...</p><form id="finish" method="post" action="/finish"><input type="hidden" name="handle" value="${escape(handle)}"><button>Continue after approval</button></form>`,
        new Headers(),
        `const form=document.getElementById('finish');const timer=setInterval(async()=>{try{const r=await fetch('/status',{method:'POST',body:new FormData(form)});if(!r.ok){clearInterval(timer);document.getElementById('state').textContent='Approval expired. Start again.';return}const s=await r.json();if(s.state!=='pending'){clearInterval(timer);form.submit()}else if(!s.online){document.getElementById('state').textContent='Phone is offline. Reconnect and start again.'}}catch{}},1500);`,
      );
    }
    if (!saved.approval) return new Response("No approval requested", { status: 409 });
    const result = await phone(env, saved.approval.phoneId).fetch(
      internal(`/approval/${saved.approval.id}`),
    );
    if (!result.ok) return new Response("Approval expired. Start again.", { status: 410 });
    const status = approvalStatus.parse(await result.json());
    if (url.pathname === "/status")
      return Response.json(status, { headers: { "Cache-Control": "no-store" } });
    if (status.state === "pending")
      return new Response("Approve on the phone first", { status: 409 });
    if (status.state === "denied") {
      const denied = await env.OAUTH_PROVIDER.denyConsent(request, handle);
      await env.OAUTH_KV.delete(`handset:consent:${handle}`);
      return new Response(null, { status: 302, headers: denied.headers });
    }
    const approved = await env.OAUTH_PROVIDER.approveConsent(request, handle);
    const completed = await env.OAUTH_PROVIDER.completeAuthorization({
      request: approved.request,
      userId: saved.approval.phoneId,
      scope: approved.request.scope,
      metadata: { clientName: saved.clientName },
      props: { phoneId: saved.approval.phoneId, grantId: saved.approval.grantId },
      revokeExistingGrants: false,
    });
    const activated = await phone(env, saved.approval.phoneId).fetch(
      internal(`/complete/${saved.approval.id}`, { method: "POST" }),
    );
    if (!activated.ok) return new Response("Approval expired or revoked. Start again.", { status: 410 });
    await env.OAUTH_KV.delete(`handset:consent:${handle}`);
    approved.headers.set("Location", completed.redirectTo);
    return new Response(null, { status: 302, headers: approved.headers });
  }
  return new Response("Not found", { status: 404 });
}
export default {
  async fetch(request: Request, env: Env, ctx: ExecutionContext): Promise<Response> {
    const url = new URL(request.url);
    try {
      const match = /^\/phone\/([A-Za-z0-9_-]{43})$/.exec(url.pathname);
      if (match?.[1]) return await phone(env, match[1]).fetch(request);
      if (Number(request.headers.get("Content-Length") ?? 0) > MAX_BODY_BYTES)
        return new Response("Request too large", { status: 413 });
      if (["/register", "/authorize"].includes(url.pathname)) {
        const ip = request.headers.get("CF-Connecting-IP") ?? "unknown";
        const { success } = await env.AUTH_LIMIT.limit({ key: `${url.pathname}:${ip}` });
        if (!success) return new Response("Too many authorization attempts. Try again later.", {
          status: 429, headers: { "Retry-After": "60" },
        });
      }
      const provider = new OAuthProvider<Env>({
        onError: () => {},
        apiRoute: "/mcp",
        authorizeEndpoint: "/authorize",
        tokenEndpoint: "/token",
        clientRegistrationEndpoint: "/register",
        resourceMetadata: {
          resource: `${url.origin}/mcp`,
          authorization_servers: [url.origin],
          resource_name: "Handset",
        },
        requiredScopes: ["phone"],
        scopesSupported: ["phone", "offline_access"],
        clientIdMetadataDocumentEnabled: true,
        accessTokenTTL: 3600,
        refreshTokenTTL: 30 * 86400,
        defaultHandler: { fetch: defaultFetch },
        apiHandler: {
          async fetch(req, bindings, context) {
            if (new URL(req.url).pathname !== "/mcp")
              return new Response("Not found", { status: 404 });
            const props = propsSchema.parse(context.props);
            const auth = z
              .object({ auth: z.object({ scope: z.array(z.string()) }) })
              .parse(context);
            if (!auth.auth.scope.includes("phone"))
              return new Response("Phone scope required", { status: 403 });
            const reader = req.body?.getReader();
            const chunks: Uint8Array[] = [];
            let size = 0;
            if (reader)
              for (;;) {
                const part = await reader.read();
                if (part.done) break;
                size += part.value.length;
                if (size > MAX_BODY_BYTES) {
                  await reader.cancel();
                  return new Response("Request too large", { status: 413 });
                }
                chunks.push(part.value);
              }
            const bytes = new Uint8Array(size);
            let offset = 0;
            for (const chunk of chunks) {
              bytes.set(chunk, offset);
              offset += chunk.length;
            }
            const body = new TextDecoder().decode(bytes);
            const headers = new Headers(req.headers);
            headers.delete("Authorization");
            headers.delete("Cookie");
            headers.set("X-Handset-Grant", props.grantId);
            headers.set("X-Handset-Method", req.method);
            return phone(bindings, props.phoneId).fetch(
              internal("/request", { method: "POST", headers, body, signal: req.signal }),
            );
          },
        },
      });
      return await provider.fetch(request, env, ctx);
    } catch (error) {
      if (error instanceof AuthorizationError && error.redirectTo)
        return Response.redirect(error.redirectTo, 302);
      return new Response("Invalid or expired request. Start again.", { status: 400 });
    }
  },
} satisfies ExportedHandler<Env>;
