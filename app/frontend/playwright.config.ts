import { defineConfig } from '@playwright/test';
export default defineConfig({
  snapshotPathTemplate: '{testDir}/screenshots/{platform}/{arg}{ext}',
  testDir: './e2e', fullyParallel: false, workers: 1,
  use: { baseURL: 'http://127.0.0.1:4173', viewport: { width: 1280, height: 720 }, browserName: 'chromium' },
  webServer: { command: 'npm run dev -- --port 4173 --strictPort', url: 'http://127.0.0.1:4173', reuseExistingServer: false, timeout: 30_000 },
});
