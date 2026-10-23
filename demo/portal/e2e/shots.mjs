// Screenshots for the public README: walks the portal, saves shots into docs/img/.
// Prereqs (same as e2e/dry-run.mjs): lite stack up, demo-seed.sh run, `npm run dev` for the portal.
import { chromium } from 'playwright-core'
import { mkdirSync, readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'

const here = (p) => fileURLToPath(new URL(p, import.meta.url))
const env = Object.fromEntries(readFileSync(here('../../../infra/local/.env'), 'utf8').split(/\r?\n/)
  .filter((l) => /^[A-Z_][A-Z0-9_]*=/.test(l)).map((l) => [l.slice(0, l.indexOf('=')), l.slice(l.indexOf('=') + 1)]))
const BASE = process.env.PORTAL ?? 'http://localhost:5173'
const CHROME = process.env.CHROME ?? 'C:/Program Files/Google/Chrome/Application/chrome.exe'
const OUT = here('../../../docs/img')
const NATIONAL_ID = '784199012345671'
mkdirSync(OUT, { recursive: true })

const browser = await chromium.launch({ executablePath: CHROME })
const go = async (page, route) => { await page.goto(`${BASE}/#/${route}`); await page.waitForTimeout(1500) }
const shot = async (page, name) => { await page.screenshot({ path: `${OUT}/${name}.png` }); console.log(`shot ${name}`) }

// Citizen context
const page = await (await browser.newContext({ viewport: { width: 1520, height: 960 }, deviceScaleFactor: 2 })).newPage()
await page.goto(`${BASE}/#/home`)
await page.evaluate(() => { localStorage.setItem('itms.lang', 'en'); sessionStorage.clear() })
await page.reload(); await page.waitForTimeout(2500)
await shot(page, 'sign-in')

await page.locator('.role-card').first().click()
await page.waitForURL(/national-id-mock/, { timeout: 30000 })
await page.fill('#username', NATIONAL_ID); await page.fill('#password', env.DEV_CUSTOMER_PASSWORD)
await page.click('#kc-login')
await page.waitForURL(/localhost:5173\/#\/dashboard/, { timeout: 30000 })
await page.waitForTimeout(4000)
await shot(page, 'citizen-dashboard')

// Staff context (its own sign-in gates)
const staffGate = async (p) => {
  if (await p.locator('input[type=password]').count()) {
    const t = p.locator('input[type=text]').first()
    if (await t.count()) { await t.fill('dev.maker') }
    await p.fill('input[type=password]', env.DEV_MAKER_PASSWORD)
    await p.locator('button.btn.primary:not(.role-card)').first().click()
    await p.waitForTimeout(4500)
  }
}
const staff = await (await browser.newContext({ viewport: { width: 1520, height: 960 }, deviceScaleFactor: 2 })).newPage()
await staff.goto(`${BASE}/#/home`)
await staff.evaluate(() => { localStorage.setItem('itms.lang', 'en'); sessionStorage.clear() })
await staff.reload(); await staff.waitForTimeout(2500)
await staff.locator('.role-card', { hasText: 'Officer' }).first().click()
await staff.waitForTimeout(2000)
await staffGate(staff)
await shot(staff, 'control-room')

const goStaff = async (route, wait) => {
  await go(staff, route); await staff.waitForTimeout(wait)
  await staffGate(staff)
}
await goStaff('backoffice', 4000); await shot(staff, 'back-office')
await goStaff('analytics', 5000); await shot(staff, 'analytics')
await goStaff('platform', 4000); await shot(staff, 'platform')

await browser.close()
console.log('done -> ' + OUT)
