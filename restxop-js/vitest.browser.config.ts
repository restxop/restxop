import { defineConfig } from "vitest/config";

export default defineConfig({
  server: { fs: { allow: [".."] } },
  test: {
    include: ["test/**/*.test.ts"],
    exclude: ["test/memory.test.ts", "test/fetch.test.ts", "node_modules/**"],
    testTimeout: 60_000,
    browser: {
      enabled: true,
      headless: true,
      provider: "playwright",
      instances: [{ browser: "chromium" }],
    },
  },
});
