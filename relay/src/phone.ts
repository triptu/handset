import { DurableObject } from "cloudflare:workers";
import { z } from "zod";
import {
  challengeText,
  fingerprint,
  grantSchema,
  MAX_BYTES,
  phoneFrame,
  rpcError,
  unbase64url,
} from "./protocol";
import type { Grant, RelayFrame } from "./protocol";

export interface PhoneEnv {
  REQUEST_TIMEOUT_MS?: string;
}
const attachmentSchema = z.object({
  nonce: z.string(),
  phoneId: z.string(),
  origin: z.string(),
  authenticated: z.boolean(),
  expiresAt: z.number(),
});
const approvalSchema = z.object({
  id: z.string().uuid(),
  grantId: z.string().uuid(),
  code: z.string(),
  clientId: z.string(),
  clientName: z.string(),
  redirectHost: z.string(),
  prompt: z.string(),
  scopes: z.array(z.string()),
  expiresAt: z.number(),
  state: z.enum(["pending", "approved", "denied"]),
});
export const approvalInput = approvalSchema.omit({ state: true });
type Approval = z.infer<typeof approvalSchema>;
type Pending = {
  grantId: string;
  finish: (response: Response) => void;
  body: string;
};

export class Phone extends DurableObject<PhoneEnv> {
  private readonly pending = new Map<string, Pending>();
  constructor(ctx: DurableObjectState, env: PhoneEnv) {
    super(ctx, env);
    ctx.setWebSocketAutoResponse(
      new WebSocketRequestResponsePair('{"type":"ping"}', '{"type":"pong"}'),
    );
  }
  private socket(): WebSocket | undefined {
    return this.ctx
      .getWebSockets()
      .find((ws) => attachmentSchema.parse(ws.deserializeAttachment()).authenticated);
  }
  private send(ws: WebSocket, frame: RelayFrame): void {
    ws.send(JSON.stringify(frame));
  }
  async fetch(request: Request): Promise<Response> {
    const url = new URL(request.url);
    if (url.pathname.startsWith("/phone/")) {
      if (request.headers.get("Upgrade")?.toLowerCase() !== "websocket")
        return new Response("WebSocket required", { status: 426 });
      const unauthenticated = this.ctx.getWebSockets().filter(
        (ws) => !attachmentSchema.parse(ws.deserializeAttachment()).authenticated,
      ).sort((a, b) =>
        attachmentSchema.parse(a.deserializeAttachment()).expiresAt -
        attachmentSchema.parse(b.deserializeAttachment()).expiresAt,
      );
      if (unauthenticated.length >= 8)
        unauthenticated[0]?.close(1008, "Replaced by a new authentication attempt");
      const phoneId = url.pathname.slice(7);
      const pair = new WebSocketPair();
      const nonce = crypto.randomUUID();
      const expiresAt = Date.now() + 15_000;
      this.ctx.acceptWebSocket(pair[1]);
      pair[1].serializeAttachment({
        nonce,
        phoneId,
        origin: url.origin,
        authenticated: false,
        expiresAt,
      });
      await this.scheduleCleanup(expiresAt);
      this.send(pair[1], { type: "challenge", nonce, phoneId, origin: url.origin });
      return new Response(null, { status: 101, webSocket: pair[0] });
    }
    if (url.pathname === "/approval" && request.method === "POST") {
      if (!this.socket())
        return Response.json(
          { error: "Phone is offline. Open Handset and reconnect." },
          { status: 503 },
        );
      const input = approvalInput.parse(await request.json());
      const existing = await this.ctx.storage.list({ prefix: "approval:" });
      if (existing.size >= 16) return new Response("Too many pending approvals", { status: 429 });
      await this.ctx.storage.put(`approval:${input.id}`, { ...input, state: "pending" });
      await this.scheduleCleanup(input.expiresAt);
      const ws = this.socket();
      if (!ws) return new Response("Phone is offline", { status: 503 });
      this.send(ws, { type: "approval_request", ...input });
      return Response.json({ state: "pending" });
    }
    if (url.pathname.startsWith("/complete/") && request.method === "POST") {
      const stored = await this.ctx.storage.get(`approval:${url.pathname.slice(10)}`);
      if (!stored) return new Response("Approval expired or revoked", { status: 410 });
      const approval = approvalSchema.parse(stored);
      if (approval.state !== "approved" || approval.expiresAt < Date.now())
        return new Response("Approval required", { status: 403 });
      await this.ctx.storage.put(`grant:${approval.grantId}`, {
        grantId: approval.grantId, clientId: approval.clientId, clientName: approval.clientName,
      });
      return new Response(null, { status: 204 });
    }
    if (url.pathname.startsWith("/approval/")) {
      const stored = await this.ctx.storage.get(`approval:${url.pathname.slice(10)}`);
      if (!stored) return new Response("Approval expired", { status: 410 });
      const approval = approvalSchema.parse(stored);
      if (approval.expiresAt < Date.now()) return new Response("Approval expired", { status: 410 });
      return Response.json({ ...approval, online: !!this.socket() });
    }
    if (url.pathname !== "/request") return new Response("Not found", { status: 404 });
    const body = await request.text();
    const grantId = request.headers.get("X-Handset-Grant") ?? "";
    if (!(await this.ctx.storage.get(`grant:${grantId}`)))
      return rpcError(
        body,
        403,
        -32003,
        "Agent access was revoked. Connect again and approve on the phone.",
      );
    const ws = this.socket();
    if (!ws) return rpcError(body, 503, -32001, "Phone is offline. Open Handset and reconnect.");
    if (this.pending.size >= 32)
      return rpcError(body, 429, -32004, "Phone has too many pending requests.");
    const id = crypto.randomUUID();
    const headers: Record<string, string> = {};
    for (const name of [
      "content-type",
      "accept",
      "mcp-protocol-version",
      "mcp-session-id",
      "last-event-id",
    ]) {
      const value = request.headers.get(name);
      if (value) headers[name] = value;
    }
    return new Promise<Response>((resolve) => {
      const timer = setTimeout(
        () => {
          try {
            const current = this.socket();
            if (current) this.send(current, { type: "cancel", id });
          } finally {
            this.pending
              .get(id)
              ?.finish(
                rpcError(
                  body,
                  504,
                  -32002,
                  "Phone request timed out. Check the phone before retrying.",
                ),
              );
          }
        },
        Number(this.env.REQUEST_TIMEOUT_MS ?? 120_000),
      );
      const abort = () => {
        try {
          const current = this.socket();
            if (current) this.send(current, { type: "cancel", id });
        } finally {
          this.pending.get(id)?.finish(new Response(null, { status: 499 }));
        }
      };
      this.pending.set(id, {
        grantId,
        body,
        finish: (response) => {
          clearTimeout(timer);
          request.signal.removeEventListener("abort", abort);
          this.pending.delete(id);
          resolve(response);
        },
      });
      request.signal.addEventListener("abort", abort, { once: true });
      if (request.signal.aborted) { abort(); return; }
      try {
        this.send(ws, {
          type: "request",
          id,
          grantId,
          method: request.headers.get("X-Handset-Method") ?? "POST",
          headers,
          body,
        });
      } catch {
        this.pending
          .get(id)
          ?.finish(rpcError(body, 503, -32001, "Phone is offline. Open Handset and reconnect."));
      }
    });
  }
  async webSocketMessage(ws: WebSocket, message: string | ArrayBuffer): Promise<void> {
    try {
      if (typeof message !== "string" || new TextEncoder().encode(message).length > MAX_BYTES)
        throw new Error("Frame too large");
      const frame = phoneFrame.parse(JSON.parse(message));
      const attachment = attachmentSchema.parse(ws.deserializeAttachment());
      if (!attachment.authenticated) {
        if (frame.type !== "hello" || Date.now() > attachment.expiresAt)
          throw new Error("Hello required");
        if ((await fingerprint(frame.publicKey)) !== attachment.phoneId)
          throw new Error("Wrong key");
        const key = await crypto.subtle.importKey(
          "spki",
          unbase64url(frame.publicKey),
          { name: "ECDSA", namedCurve: "P-256" },
          false,
          ["verify"],
        );
        if (
          !(await crypto.subtle.verify(
            { name: "ECDSA", hash: "SHA-256" },
            key,
            unbase64url(frame.signature),
            challengeText(attachment.origin, attachment.phoneId, attachment.nonce),
          ))
        )
          throw new Error("Wrong signature");
        for (const old of this.ctx.getWebSockets())
          if (old !== ws && attachmentSchema.parse(old.deserializeAttachment()).authenticated)
            {
              old.serializeAttachment({ ...attachmentSchema.parse(old.deserializeAttachment()), authenticated: false });
              old.close(4001, "Reconnected");
            }
        ws.serializeAttachment({ ...attachment, authenticated: true });
        const grants = await this.ctx.storage.list<Grant>({ prefix: "grant:" });
        this.send(ws, {
          type: "hello",
          version: 1,
          grants: [...grants.values()].map((g) => grantSchema.parse(g)),
        });
        return;
      }
      switch (frame.type) {
        case "response": {
          const pending = this.pending.get(frame.id);
          if (pending)
            pending.finish(
              frame.status === 204 || frame.status === 205 || frame.status === 304
                ? new Response(null, { status: frame.status })
                : Response.json(frame.json, { status: frame.status }),
            );
          break;
        }
        case "approval_answer": {
          const stored = await this.ctx.storage.get(`approval:${frame.id}`);
          if (!stored) break;
          const approval = approvalSchema.parse(stored);
          if (approval.state !== "pending" || approval.expiresAt < Date.now()) break;
          await this.ctx.storage.transaction(async (storage) => {
            await storage.put(`approval:${frame.id}`, {
              ...approval,
              state: frame.allow ? "approved" : "denied",
            });

          });
          break;
        }
        case "revoke":
          await this.ctx.storage.delete(`grant:${frame.grantId}`);
          for (const [key, approval] of await this.ctx.storage.list<Approval>({ prefix: "approval:" }))
            if (approval.grantId === frame.grantId) await this.ctx.storage.delete(key);
          for (const [id, p] of this.pending)
            if (p.grantId === frame.grantId) {
              const current = this.socket();
            if (current) this.send(current, { type: "cancel", id });
              p.finish(rpcError(p.body, 403, -32003, "Agent access was revoked."));
            }
          this.send(ws, { type: "revoked", grantId: frame.grantId });
          break;
        case "hello":
          throw new Error("Already authenticated");
      }
    } catch {
      ws.close(1008, "Invalid relay frame or authentication");
    }
  }
  webSocketClose(_ws: WebSocket): void {
    // Pending calls survive a transport disconnect until a reply, cancellation, or timeout.
  }
  webSocketError(ws: WebSocket): void {
    ws.close(1011, "Connection error");
  }
  private async scheduleCleanup(at: number): Promise<void> {
    const alarm = await this.ctx.storage.getAlarm();
    if (alarm === null || alarm > at) await this.ctx.storage.setAlarm(at);
  }
  async alarm(): Promise<void> {
    let next = Infinity;
    for (const ws of this.ctx.getWebSockets()) {
      const a = attachmentSchema.parse(ws.deserializeAttachment());
      if (!a.authenticated) {
        if (a.expiresAt <= Date.now()) ws.close(1008, "Authentication expired");
        else next = Math.min(next, a.expiresAt);
      }
    }
    for (const [key, value] of await this.ctx.storage.list<Approval>({ prefix: "approval:" })) {
      if (value.expiresAt <= Date.now()) await this.ctx.storage.delete(key);
      else next = Math.min(next, value.expiresAt);
    }
    if (next !== Infinity) await this.ctx.storage.setAlarm(next);
  }
}
