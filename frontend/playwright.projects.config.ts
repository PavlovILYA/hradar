import { defineConfig } from '@playwright/test';
export default defineConfig({testDir: './tests', timeout:45000, workers:1, use:{baseURL:'http://127.0.0.1:5175',headless:true},webServer:{command:'npm run dev -- --port 5175',url:'http://127.0.0.1:5175',reuseExistingServer:false}});
