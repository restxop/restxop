import { defineConfig } from "vitest/config";

export default defineConfig({
  server: { fs: { allow: [".."] } },
  test: {
    include: ["test/**/*.test.ts"],
    testTimeout: 30_000,
  },
});
