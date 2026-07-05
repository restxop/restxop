// SC-006 bundle budget: the built entry graph must stay under 10 KB gzipped.
import { readdirSync, readFileSync, statSync } from "node:fs";
import { gzipSync } from "node:zlib";
import { join } from "node:path";

const BUDGET = 10 * 1024;
const dist = new URL("../dist", import.meta.url).pathname;

let total = 0;
for (const file of readdirSync(dist)) {
  if (!file.endsWith(".js")) continue;
  const path = join(dist, file);
  if (!statSync(path).isFile()) continue;
  total += gzipSync(readFileSync(path), { level: 9 }).length;
}

console.log(`bundle size (gzip, all dist js): ${total} bytes (budget ${BUDGET})`);
if (total === 0) {
  console.error("no built output found — run `npm run build` first");
  process.exit(1);
}
if (total > BUDGET) {
  console.error(`BUNDLE BUDGET EXCEEDED (SC-006): ${total} > ${BUDGET}`);
  process.exit(1);
}
