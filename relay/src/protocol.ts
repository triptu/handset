import { z } from "zod";

export const MAX_BYTES = 1024 * 1024;
export const MAX_BODY_BYTES = 256 * 1024;
export const PHONE_ID = /^[A-Za-z0-9_-]{43}$/;
export const phoneFrame = z.discriminatedUnion("type", [
  z.object({
    type: z.literal("hello"),
    version: z.literal(1),
    publicKey: z.string().max(256),
    signature: z.string().max(128),
  }),
  z.object({
    type: z.literal("response"),
    id: z.string().uuid(),
    status: z.number().int().min(200).max(599),
    json: z.json(),
  }),
  z.object({ type: z.literal("approval_answer"), id: z.string().uuid(), allow: z.boolean() }),
  z.object({ type: z.literal("revoke"), grantId: z.string().uuid() }),
]);
export const grantSchema = z.object({
  grantId: z.string().uuid(),
  clientId: z.string(),
  clientName: z.string(),
});
export type Grant = z.infer<typeof grantSchema>;
export type RelayFrame =
  | { type: "challenge"; nonce: string; phoneId: string; origin: string }
  | { type: "hello"; version: 1; grants: Grant[] }
  | {
      type: "request";
      id: string;
      grantId: string;
      method: string;
      headers: Record<string, string>;
      body: string;
    }
  | {
      type: "approval_request";
      id: string;
      grantId: string;
      code: string;
      clientId: string;
      clientName: string;
      redirectHost: string;
      prompt: string;
      scopes: string[];
      expiresAt: number;
    }
  | { type: "revoked"; grantId: string }
  | { type: "cancel"; id: string };
export const relayFrame = z.discriminatedUnion("type", [
  z.object({
    type: z.literal("challenge"),
    nonce: z.string(),
    phoneId: z.string(),
    origin: z.string(),
  }),
  z.object({ type: z.literal("hello"), version: z.literal(1), grants: z.array(grantSchema) }),
  z.object({
    type: z.literal("request"),
    id: z.string(),
    grantId: z.string(),
    method: z.string(),
    headers: z.record(z.string(), z.string()),
    body: z.string(),
  }),
  z.object({
    type: z.literal("approval_request"),
    id: z.string(),
    grantId: z.string(),
    code: z.string(),
    clientId: z.string(),
    clientName: z.string(),
    redirectHost: z.string(),
    prompt: z.string(),
    scopes: z.array(z.string()),
    expiresAt: z.number(),
  }),
  z.object({ type: z.literal("revoked"), grantId: z.string() }),
  z.object({ type: z.literal("cancel"), id: z.string() }),
]);
export function base64url(bytes: Uint8Array): string {
  return btoa(String.fromCharCode(...bytes))
    .replaceAll("+", "-")
    .replaceAll("/", "_")
    .replaceAll("=", "");
}
export function unbase64url(value: string): Uint8Array<ArrayBuffer> {
  return Uint8Array.from(atob(value.replaceAll("-", "+").replaceAll("_", "/")), (c) =>
    c.charCodeAt(0),
  );
}
export async function fingerprint(publicKey: string): Promise<string> {
  return base64url(new Uint8Array(await crypto.subtle.digest("SHA-256", unbase64url(publicKey))));
}
export function challengeText(
  origin: string,
  phoneId: string,
  nonce: string,
): Uint8Array<ArrayBuffer> {
  return new Uint8Array(
    new TextEncoder().encode(`handset-relay-v1\n${origin}\n${phoneId}\n${nonce}`),
  );
}
export function rpcError(body: string, status: number, code: number, message: string): Response {
  let id: unknown = null;
  try {
    id = z.object({ id: z.union([z.string(), z.number(), z.null()]) }).parse(JSON.parse(body)).id;
  } catch {
    /* Notifications have no id. */
  }
  return Response.json({ jsonrpc: "2.0", id, error: { code, message } }, { status });
}
