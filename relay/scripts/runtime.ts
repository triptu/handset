import { build } from "esbuild";
import { Miniflare } from "miniflare";

export async function runtime({
  port = 8787,
  timeout = 120_000,
}: { port?: number; timeout?: number } = {}) {
  const bundle = await build({
    entryPoints: [new URL("../src/worker.ts", import.meta.url).pathname],
    bundle: true,
    write: false,
    format: "esm",
    platform: "neutral",
    external: ["cloudflare:*"],
    mainFields: ["module", "main"],
    conditions: ["workerd", "worker", "browser"],
    logLevel: "silent",
  });
  const script = bundle.outputFiles[0]?.text;
  if (!script) throw new Error("Worker bundle missing");
  const mf = new Miniflare({
    modules: true,
    script,
    compatibilityDate: "2026-07-30",
    compatibilityFlags: ["nodejs_compat", "global_fetch_strictly_public", "enable_request_signal", "request_signal_passthrough"],
    // Tests use the runtime socket so TCP resets reach workerd directly.
    ...(port === 0 ? { unsafeDirectSockets: [{ host: "127.0.0.1", port: 0 }] } : {}),
    port,
    host: "127.0.0.1",
    ratelimits: { AUTH_LIMIT: { namespace_id: "1001", simple: { limit: 10, period: 60 } } },
    kvNamespaces: ["OAUTH_KV"],
    durableObjects: { PHONES: { className: "Phone", useSQLite: true } },
    bindings: { REQUEST_TIMEOUT_MS: String(timeout) },
    logRequests: false,
  });
  const ready = await mf.ready;
  const url = port === 0 ? await mf.unsafeGetDirectURL() : ready;
  return { mf, url: url.origin };
}
