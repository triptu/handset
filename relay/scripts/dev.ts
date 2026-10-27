import { runtime } from "./runtime";
const { mf, url } = await runtime();
console.log(`Handset relay: ${url}`);
for (const signal of ["SIGINT", "SIGTERM"] satisfies NodeJS.Signals[])
  process.on(signal, async () => {
    await mf.dispose();
    process.exit(0);
  });
