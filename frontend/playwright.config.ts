import {defineConfig,devices} from '@playwright/test';

const CI=Boolean(process.env.CI);

export default defineConfig({
  testDir:'./e2e',
  timeout:75_000,
  expect:{timeout:20_000},
  fullyParallel:false,
  retries:CI?1:0,
  workers:1,
  reporter:CI?[['list'],['html',{open:'never'}],['junit',{outputFile:'test-results/junit.xml'}]]:'list',
  outputDir:'test-results/artifacts',
  use:{
    baseURL:'http://127.0.0.1:5173',
    trace:'retain-on-failure',
    screenshot:'only-on-failure',
    video:'retain-on-failure',
    actionTimeout:15_000,
  },
  projects:[{
    name:'chromium',
    use:{...devices['Desktop Chrome'],headless:true}
  }],
  webServer:{
    command:'npm run dev',
    url:'http://127.0.0.1:5173',
    reuseExistingServer:!CI,
    timeout:120_000
  }
});
