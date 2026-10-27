// Test-only browser stand-in. The fake phone, not this script, approves the grant.
export async function approveTestClient(authorizeUrl: string, phoneId: string, testHeaders: Record<string, string> = {}, beforeFinish?: () => Promise<void>) {
  const origin = new URL(authorizeUrl).origin;
  const first = await fetch(authorizeUrl, { redirect: "manual", headers: testHeaders });
  const html = await first.text();
  const handle = /name="handle" value="([^"]+)"/.exec(html)?.[1];
  if (!handle) throw new Error(`Authorization form missing (${first.status}): ${html}`);
  const cookie = first.headers
    .getSetCookie()
    .map((c) => c.split(";")[0])
    .join("; ");
  const headers = { ...testHeaders, Origin: origin, Cookie: cookie };
  const asked = await fetch(`${origin}/authorize`, {
    method: "POST",
    headers,
    body: new URLSearchParams({ handle, phoneId }),
  });
  const askedHtml = await asked.text();
  if (!askedHtml.includes("Waiting for the phone"))
    throw new Error(`Approval failed: ${askedHtml}`);
  for (let i = 0; i < 50; i++) {
    const r = await fetch(`${origin}/status`, {
      method: "POST",
      headers,
      body: new URLSearchParams({ handle }),
    });
    const status = await r.json();
    if (
      typeof status === "object" &&
      status !== null &&
      "state" in status &&
      status.state !== "pending"
    ) {
      await beforeFinish?.();
      const finish = await fetch(`${origin}/finish`, {
        method: "POST",
        headers,
        body: new URLSearchParams({ handle }),
        redirect: "manual",
      });
      const callback = finish.headers.get("location");
      if (!callback) throw new Error(`OAuth callback missing: ${await finish.text()}`);
      return callback;
    }
    await Bun.sleep(100);
  }
  throw new Error("Fake phone did not approve");
}
if (import.meta.main) {
  const [, , url, phoneId] = process.argv;
  if (!url || !phoneId)
    throw new Error("Usage: bun scripts/approve-test-client.ts <authorization-url> <phone-id>");
  const callback = await approveTestClient(url, phoneId);
  const result = await fetch(callback);
  console.log(`OAuth callback HTTP ${result.status}`);
}
