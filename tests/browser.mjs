import { createRequire } from 'node:module';
import { existsSync } from 'node:fs';

const require = createRequire(import.meta.url);
const { chromium } = require('playwright');
const macChrome = '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome';

export function launchBrowser() {
  const executablePath =
    process.env.STILL_BROWSER || (existsSync(macChrome) ? macChrome : undefined);
  return chromium.launch({ headless: true, executablePath });
}
