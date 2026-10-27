import * as Alchemy from "alchemy";
import * as Cloudflare from "@alchemy.run/cloudflare";
import * as Effect from "effect/Effect";

export default Alchemy.Stack(
  "handset-relay",
  {
    providers: Cloudflare.providers(),
    state: Cloudflare.state(),
  },
  Effect.gen(function* () {
    const oauth = yield* Cloudflare.KV.Namespace("oauth");
    const worker = yield* Cloudflare.Worker("relay", {
      main: "./src/worker.ts",
      compatibility: {
        date: "2026-10-22",
        flags: ["nodejs_compat", "global_fetch_strictly_public", "enable_request_signal", "request_signal_passthrough"],
      },
      env: {
        PHONES: Cloudflare.DurableObject("Phone", { storage: "sqlite" }),
        OAUTH_KV: oauth,
        AUTH_LIMIT: Cloudflare.RateLimit("AUTH_LIMIT", {
          namespaceId: 1001,
          simple: { limit: 10, period: 60 },
        }),
      },
      workersDev: true,
      observability: { enabled: false },
    });
    return { url: worker.url };
  }),
);
