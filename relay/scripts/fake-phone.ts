import { z } from "zod";
import { base64url, challengeText, fingerprint, relayFrame } from "../src/protocol";

export async function makeIdentity() {
  const keys = await crypto.subtle.generateKey({ name: "ECDSA", namedCurve: "P-256" }, true, [
    "sign",
    "verify",
  ]);
  if (!("privateKey" in keys)) throw new Error("Expected key pair");
  const spki = await crypto.subtle.exportKey("spki", keys.publicKey);
  if (!(spki instanceof ArrayBuffer)) throw new Error("Expected SPKI bytes");
  const publicKey = base64url(new Uint8Array(spki));
  const privateKey = await crypto.subtle.exportKey("jwk", keys.privateKey);
  return { publicKey, privateKey, phoneId: await fingerprint(publicKey) };
}
export type Identity = Awaited<ReturnType<typeof makeIdentity>>;
const rpcSchema = z.object({
  jsonrpc: z.literal("2.0"),
  id: z.union([z.string(), z.number()]).optional(),
  method: z.string(),
  params: z.object({ protocolVersion: z.string().optional() }).passthrough().optional(),
});
export async function connectPhone(
  url: string,
  identity: Identity,
  options: { approve?: boolean; delay?: (body: string) => number; ignore?: boolean } = {},
) {
  const key = await crypto.subtle.importKey(
    "jwk",
    identity.privateKey,
    { name: "ECDSA", namedCurve: "P-256" },
    false,
    ["sign"],
  );
  const socketUrl = new URL(`/phone/${identity.phoneId}`, url);
  socketUrl.protocol = socketUrl.protocol === "https:" ? "wss:" : "ws:";
  const ws = new WebSocket(socketUrl.href);
  const grants: string[] = [];
  const cancellations: string[] = [];
  let readyResolve: () => void;
  let readyReject: (reason: Error) => void;
  const ready = new Promise<void>((resolve, reject) => {
    readyResolve = resolve;
    readyReject = reject;
  });
  const send = (frame: unknown) => ws.send(JSON.stringify(frame));
  ws.addEventListener("error", () => readyReject(new Error("Fake phone connection failed")));
  ws.addEventListener("message", async (event) => {
    const parsed = relayFrame.safeParse(JSON.parse(String(event.data)));
    if (!parsed.success) return;
    const frame = parsed.data;
    switch (frame.type) {
      case "challenge": {
        const origin = new URL(url).origin;
        if (frame.origin !== origin || frame.phoneId !== identity.phoneId)
          throw new Error("Relay identity mismatch");
        const signature = await crypto.subtle.sign(
          { name: "ECDSA", hash: "SHA-256" },
          key,
          challengeText(origin, identity.phoneId, frame.nonce),
        );
        send({
          type: "hello",
          version: 1,
          publicKey: identity.publicKey,
          signature: base64url(new Uint8Array(signature)),
        });
        break;
      }
      case "hello":
        grants.push(...frame.grants.map((g) => g.grantId));
        readyResolve();
        break;
      case "approval_request":
        if (options.approve !== false) {
          grants.push(frame.grantId);
          send({ type: "approval_answer", id: frame.id, allow: true });
        }
        break;
      case "request": {
        if (options.ignore) break;
        const delay = options.delay?.(frame.body) ?? 0;
        if (delay) await Bun.sleep(delay);
        if (ws.readyState !== WebSocket.OPEN || cancellations.includes(frame.id)) break;
        if (frame.method !== "POST") {
          send({
            type: "response",
            id: frame.id,
            status: 405,
            json: { error: "Stateless JSON POST transport" },
          });
          break;
        }
        const rpc = rpcSchema.parse(JSON.parse(frame.body));
        if (rpc.id === undefined) {
          send({ type: "response", id: frame.id, status: 204, json: null });
          break;
        }
        let result: unknown;
        switch (rpc.method) {
          case "initialize":
            result = {
              protocolVersion: rpc.params?.protocolVersion ?? "2025-11-25",
              capabilities: { tools: {} },
              serverInfo: { name: "handset-fake-phone", version: "1.0.0" },
            };
            break;
          case "tools/list":
            result = {
              tools: [
                {
                  name: "device_status",
                  description: "Read the phone battery and charging status.",
                  inputSchema: { type: "object", properties: {}, additionalProperties: false },
                  annotations: { readOnlyHint: true },
                },
              ],
            };
            break;
          case "tools/call":
            result = {
              content: [
                {
                  type: "text",
                  text: JSON.stringify({ battery: 73, charging: false, fakePhone: true }),
                },
              ],
            };
            break;
          case "ping":
            result = {};
            break;
          default:
            send({
              type: "response",
              id: frame.id,
              status: 200,
              json: {
                jsonrpc: "2.0",
                id: rpc.id,
                error: { code: -32601, message: "Method not found" },
              },
            });
            return;
        }
        send({
          type: "response",
          id: frame.id,
          status: 200,
          json: { jsonrpc: "2.0", id: rpc.id, result },
        });
        break;
      }
      case "cancel":
        cancellations.push(frame.id);
        break;
      case "revoked":
        break;
    }
  });
  await Promise.race([
    ready,
    Bun.sleep(10_000).then(() => {
      throw new Error("Phone authentication timed out");
    }),
  ]);
  return { ws, grants, cancellations, send, close: () => ws.close(1000) };
}
if (import.meta.main) {
  const url = process.argv[2] ?? "http://127.0.0.1:8787";
  const path = process.env.HANDSET_FAKE_IDENTITY;
  // Identity contains a private key. Keep it outside the checkout, with mode 0600.
  const identity: Identity =
    path && (await Bun.file(path).exists())
      ? JSON.parse(await Bun.file(path).text())
      : await makeIdentity();
  if (path) {
    await Bun.write(path, JSON.stringify(identity));
    await import("node:fs/promises").then((fs) => fs.chmod(path, 0o600));
  }
  const phone = await connectPhone(url, identity);
  console.log(`Fake phone online. Phone ID: ${identity.phoneId}`);
  console.log("TEST ONLY: this phone automatically allows every authorization request.");
  for (const signal of ["SIGINT", "SIGTERM"] satisfies NodeJS.Signals[])
    process.on(signal, () => {
      phone.close();
      process.exit(0);
    });
}
