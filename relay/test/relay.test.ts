import { createPublicKey, verify } from "node:crypto";
import signatureVector from "./fixtures/p256-signature.json";
import { connect } from "node:net";
import { afterAll, beforeAll, expect, test } from "bun:test";
import { runtime } from "../scripts/runtime";
import { connectPhone, makeIdentity } from "../scripts/fake-phone";
import { approveTestClient } from "../scripts/approve-test-client";
import { z } from "zod";
import { base64url } from "../src/protocol";

let nextIp = 1;
let running: Awaited<ReturnType<typeof runtime>>;
beforeAll(async () => {
  running = await runtime({ port: 0, timeout: 180 });
});
afterAll(async () => {
  await running.mf.dispose();
});
async function authorize(identity: Awaited<ReturnType<typeof makeIdentity>>, beforeFinish?: () => Promise<void>) {
  const { url } = running;
  const testHeaders = { "CF-Connecting-IP": `192.0.2.${nextIp++}` };
  const registered = await fetch(`${url}/register`, {
    method: "POST",
    headers: { ...testHeaders, "Content-Type": "application/json" },
    body: JSON.stringify({
      client_name: "Relay test",
      redirect_uris: ["http://127.0.0.1:19999/callback"],
      grant_types: ["authorization_code", "refresh_token"],
      response_types: ["code"],
      token_endpoint_auth_method: "none",
    }),
  });
  const client = z.object({ client_id: z.string() }).parse(await registered.json());
  const verifier = base64url(crypto.getRandomValues(new Uint8Array(32)));
  const challenge = base64url(
    new Uint8Array(await crypto.subtle.digest("SHA-256", new TextEncoder().encode(verifier))),
  );
  const query = new URLSearchParams({
    response_type: "code",
    client_id: client.client_id,
    redirect_uri: "http://127.0.0.1:19999/callback",
    scope: "phone offline_access",
    resource: `${url}/mcp`,
    code_challenge: challenge,
    code_challenge_method: "S256",
    state: "test-state",
  });
  const callback = await approveTestClient(`${url}/authorize?${query}`, identity.phoneId, testHeaders, beforeFinish);
  const code = new URL(callback).searchParams.get("code");
  if (!code) throw new Error("Authorization code missing");
  const response = await fetch(`${url}/token`, {
    method: "POST",
    body: new URLSearchParams({
      grant_type: "authorization_code",
      code,
      client_id: client.client_id,
      code_verifier: verifier,
      redirect_uri: "http://127.0.0.1:19999/callback",
      resource: `${url}/mcp`,
    }),
  });
  const tokens = z
    .object({ access_token: z.string(), refresh_token: z.string() })
    .parse(await response.json());
  expect(response.status).toBe(200);
  expect(tokens.access_token).toBeString();
  return {
    token: String(tokens.access_token),
    refresh: String(tokens.refresh_token),
    clientId: String(client.client_id),
  };
}
function call(token: string, id: number) {
  return fetch(`${running.url}/mcp`, {
    method: "POST",
    headers: {
      Authorization: `Bearer ${token}`,
      "Content-Type": "application/json",
      Accept: "application/json, text/event-stream",
    },
    body: JSON.stringify({ jsonrpc: "2.0", id, method: "tools/list" }),
  });
}
test("discovery requires OAuth and advertises DCR and client metadata documents", async () => {
  expect((await call("invalid", 1)).status).toBe(401);
  const m = z
    .object({
      registration_endpoint: z.string(),
      client_id_metadata_document_supported: z.boolean(),
    })
    .parse(
      await fetch(`${running.url}/.well-known/oauth-authorization-server`).then((r) => r.json()),
    );
  expect(m.registration_endpoint).toBe(`${running.url}/register`);
  expect(m.client_id_metadata_document_supported).toBe(true);
});
test("offline phone returns an immediate JSON-RPC error with the request id", async () => {
  const identity = await makeIdentity();
  const phone = await connectPhone(running.url, identity);
  const auth = await authorize(identity);
  phone.close();
  await Bun.sleep(50);
  const response = await call(auth.token, 42);
  expect(response.status).toBe(503);
  expect(await response.json()).toMatchObject({ jsonrpc: "2.0", id: 42, error: { code: -32001 } });
});
test("two simultaneous requests match their own responses in reverse completion order", async () => {
  const identity = await makeIdentity();
  const phone = await connectPhone(running.url, identity, {
    delay: (body) => (JSON.parse(body).id === 1 ? 80 : 5),
  });
  try {
    const auth = await authorize(identity);
    const finished: number[] = [];
    const responses = await Promise.all(
      [1, 2].map(async (id) => {
        const response = await call(auth.token, id);
        finished.push(id);
        return z.object({ id: z.number() }).parse(await response.json());
      }),
    );
    expect(finished).toEqual([2, 1]);
    expect(responses.map((r) => r.id)).toEqual([1, 2]);
  } finally {
    phone.close();
  }
});
test("phone revocation immediately refuses old and refreshed tokens", async () => {
  const identity = await makeIdentity();
  const phone = await connectPhone(running.url, identity);
  try {
    const auth = await authorize(identity);
    expect((await call(auth.token, 1)).status).toBe(200);
    phone.send({ type: "revoke", grantId: phone.grants[0] });
    await Bun.sleep(30);
    expect((await call(auth.token, 2)).status).toBe(403);
    const refreshed = await fetch(`${running.url}/token`, {
      method: "POST",
      body: new URLSearchParams({
        grant_type: "refresh_token",
        refresh_token: auth.refresh,
        client_id: auth.clientId,
        resource: `${running.url}/mcp`,
      }),
    }).then((r) => r.json());
    const token = z.object({ access_token: z.string() }).parse(refreshed).access_token;
    expect((await call(token, 3)).status).toBe(403);
  } finally {
    phone.close();
  }
});
test("timeout returns a JSON-RPC error and cancels the phone call", async () => {
  const identity = await makeIdentity();
  const phone = await connectPhone(running.url, identity, { ignore: true });
  try {
    const auth = await authorize(identity);
    const response = await call(auth.token, 7);
    expect(response.status).toBe(504);
    expect(await response.json()).toMatchObject({ id: 7, error: { code: -32002 } });
    await Bun.sleep(10);
    expect(phone.cancellations.length).toBe(1);
  } finally {
    phone.close();
  }
});
test("a different key cannot impersonate an existing phone", async () => {
  const identity = await makeIdentity();
  const impostor = await makeIdentity();
  const ws = new WebSocket(`${running.url.replace("http:", "ws:")}/phone/${identity.phoneId}`);
  const closed = new Promise<number>((resolve) =>
    ws.addEventListener("close", (e) => resolve(e.code)),
  );
  ws.addEventListener("message", () =>
    ws.send(
      JSON.stringify({
        type: "hello",
        version: 1,
        publicKey: impostor.publicKey,
        signature: base64url(new Uint8Array(64)),
      }),
    ),
  );
  expect(await closed).toBe(1008);
});

test("reconnect restores grants and the hibernation heartbeat answers", async () => {
  const identity = await makeIdentity();
  const first = await connectPhone(running.url, identity);
  const auth = await authorize(identity);
  const grantId = z.string().uuid().parse(first.grants[0]);
  first.close();
  await Bun.sleep(20);
  const second = await connectPhone(running.url, identity);
  try {
    expect(second.grants).toContain(grantId);
    const pong = new Promise<string>((resolve) =>
      second.ws.addEventListener("message", (e) => resolve(String(e.data)), { once: true }),
    );
    second.ws.send('{"type":"ping"}');
    expect(await pong).toBe('{"type":"pong"}');
    expect((await call(auth.token, 9)).status).toBe(200);
  } finally {
    second.close();
  }
});

test("a pending call accepts its answer on the authenticated replacement socket without replay", async () => {
  const identity = await makeIdentity();
  const first = await connectPhone(running.url, identity, { ignore: true });
  const auth = await authorize(identity);
  const request = new Promise<string>((resolve) => first.ws.addEventListener("message", (e) => {
    const frame = JSON.parse(String(e.data));
    if (frame.type === "request") resolve(frame.id);
  }));
  const response = call(auth.token, 10);
  const id = await request;
  first.close();
  const second = await connectPhone(running.url, identity, { ignore: true });
  try {
    second.send({ type: "response", id, status: 200, json: { jsonrpc: "2.0", id: 10, result: { resumed: true } } });
    expect(await (await response).json()).toMatchObject({ id: 10, result: { resumed: true } });
  } finally { second.close(); }
});

 test("unauthenticated sockets cannot lock out the real phone", async () => {
  const identity = await makeIdentity();
  const sockets: WebSocket[] = [];
  try {
    for (let i = 0; i < 9; i++) {
      const ws = new WebSocket(`${running.url.replace("http:", "ws:")}/phone/${identity.phoneId}`);
      sockets.push(ws);
      await new Promise<void>((resolve) => ws.addEventListener("message", () => resolve(), { once: true }));
    }
    const phone = await connectPhone(running.url, identity);
    phone.close();
    await Bun.sleep(20);
    expect(sockets.filter((ws) => ws.readyState !== WebSocket.OPEN).length).toBeGreaterThan(0);
  } finally {
    for (const ws of sockets) ws.close();
  }
});

test("registration and authorization are rate limited before OAuth writes", async () => {
  for (const path of ["/register", "/authorize"]) {
    const responses = [];
    for (let i = 0; i < 11; i++) responses.push(await fetch(`${running.url}${path}`, {
      method: path === "/register" ? "POST" : "GET",
      headers: { "CF-Connecting-IP": "192.0.2.100", "Content-Type": "application/json" },
      ...(path === "/register" ? { body: "{}" } : {}),
    }));
    expect(responses[10]?.status).toBe(429);
    expect(responses[10]?.headers.get("Retry-After")).toBe("60");
  }
});

test("client disconnect cancels the phone call and releases its pending slot", async () => {
  const identity = await makeIdentity();
  const phone = await connectPhone(running.url, identity, { ignore: true });
  try {
    const auth = await authorize(identity);
    const received = new Promise<string>((resolve) => phone.ws.addEventListener("message", (e) => {
      const frame = JSON.parse(String(e.data));
      if (frame.type === "request") resolve(frame.id);
    }));
    const target = new URL(running.url);
    const client = connect(Number(target.port), target.hostname);
    client.on("error", () => {});
    const body = JSON.stringify({ jsonrpc: "2.0", id: 99, method: "tools/list" });
    await new Promise<void>((resolve) => client.once("connect", resolve));
    client.write(`POST /mcp HTTP/1.1\r\nHost: ${target.host}\r\nAuthorization: Bearer ${auth.token}\r\nContent-Type: application/json\r\nContent-Length: ${Buffer.byteLength(body)}\r\n\r\n${body}`);
    const id = await received;
    client.resetAndDestroy();
    for (let i = 0; i < 10 && !phone.cancellations.includes(id); i++) await Bun.sleep(5);
    expect(phone.cancellations).toContain(id);
    // Fill every slot before the timeout. A leaked cancelled call would make one return 429.
    const ids: string[] = [];
    const full = new Promise<void>((resolve) => phone.ws.addEventListener("message", (e) => {
      const frame = JSON.parse(String(e.data));
      if (frame.type === "request") {
        ids.push(frame.id);
        if (ids.length === 32) resolve();
      }
    }));
    const calls = Array.from({ length: 32 }, (_, i) => call(auth.token, 100 + i));
    await Promise.race([full, Bun.sleep(100).then(() => { throw new Error("Pending slot was not released"); })]);
    for (const id of ids) phone.send({ type: "response", id, status: 204, json: null });
    expect((await Promise.all(calls)).every((r) => r.status === 204)).toBe(true);
  } finally { phone.close(); }
});

test("phone approval creates no active grant until OAuth completes", async () => {
  const identity = await makeIdentity();
  const first = await connectPhone(running.url, identity);
  let second: Awaited<ReturnType<typeof connectPhone>> | undefined;
  try {
    const auth = await authorize(identity, async () => {
      second = await connectPhone(running.url, identity);
      expect(second.grants).toEqual([]);
    });
    expect((await call(auth.token, 81)).status).toBe(200);
    const third = await connectPhone(running.url, identity);
    expect(third.grants.length).toBe(1);
    third.close();
  } finally { first.close(); second?.close(); }
});

test("the documented DER signature converts to P1363 and both signatures verify", () => {
  const der = Buffer.from(signatureVector.derHex, "hex");
  let offset = 2;
  const integers = [];
  for (let i = 0; i < 2; i++) {
    expect(der[offset++]).toBe(2);
    const length = der[offset++];
    if (length === undefined) throw new Error("Missing integer length");
    let integer = der.subarray(offset, offset + length);
    offset += length;
    if (integer[0] === 0) integer = integer.subarray(1);
    integers.push(Buffer.concat([Buffer.alloc(32 - integer.length), integer]));
  }
  const raw = Buffer.concat(integers);
  expect(raw.toString("hex")).toBe(signatureVector.p1363Hex);
  const key = createPublicKey({ key: Buffer.from(signatureVector.publicKeySpkiHex, "hex"), format: "der", type: "spki" });
  const message = Buffer.from(signatureVector.messageUtf8, "utf8");
  expect(verify("sha256", message, key, der)).toBe(true);
  expect(verify("sha256", message, { key, dsaEncoding: "ieee-p1363" }, raw)).toBe(true);
});
