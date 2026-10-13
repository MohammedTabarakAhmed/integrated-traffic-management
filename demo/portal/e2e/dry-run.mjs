// Demo dry run: walks the demo script in a real (headless) Chrome against the running local stack and prints PASS/FAIL
// per step, with screenshots in e2e/out/. Needs: the lite stack up, tools/dev/demo-seed.sh run, `npm run dev`.
//   node e2e/dry-run.mjs            (passwords read from infra/local/.env)
//   CHROME=/path/to/chrome node e2e/dry-run.mjs
// The OTP step-up (Contact & alerts) is not driven here: it needs the presenter's authenticator app.
import { chromium } from 'playwright-core'
import { mkdirSync, readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'

const here = (p) => fileURLToPath(new URL(p, import.meta.url))
const env = Object.fromEntries(readFileSync(here('../../../infra/local/.env'), 'utf8').split(/\r?\n/)
  .filter((l) => /^[A-Z_][A-Z0-9_]*=/.test(l)).map((l) => [l.slice(0, l.indexOf('=')), l.slice(l.indexOf('=') + 1)]))
const BASE = process.env.PORTAL ?? 'http://localhost:5173'
const CHROME = process.env.CHROME ?? 'C:/Program Files/Google/Chrome/Application/chrome.exe'
const NATIONAL_ID = '784199012345671'
mkdirSync(here('./out'), { recursive: true })

const browser = await chromium.launch({ executablePath: CHROME })
const page = await (await browser.newContext({ viewport: { width: 1440, height: 950 } })).newPage()
const pageErrors = []
page.on('pageerror', (e) => pageErrors.push(e.message))

const results = []
async function step(name, fn) {
  const t = Date.now()
  try {
    const note = await fn()
    results.push(['PASS', name, note ?? '', Date.now() - t])
    console.log(`PASS  ${name}${note ? ` — ${note}` : ''}`)
  } catch (e) {
    results.push(['FAIL', name, e.message.split('\n')[0], Date.now() - t])
    console.log(`FAIL  ${name} — ${e.message.split('\n')[0]}`)
  }
  await page.screenshot({ path: here(`./out/${String(results.length).padStart(2, '0')}-${name.replace(/\W+/g, '-').toLowerCase()}.png`) }).catch(() => {})
}
const go = async (route) => { await page.goto(`${BASE}/#/${route}`); await page.waitForTimeout(800) }
const expect = (cond, msg) => { if (!cond) throw new Error(msg) }

await page.goto(BASE)
await page.evaluate(() => { localStorage.setItem('itms.lang', 'en'); sessionStorage.clear() })

await step('Overview loads with the platform figures', async () => {
  await go('home'); await page.reload(); await page.waitForTimeout(1500)
  expect(await page.locator('.stat').count() === 4, 'stats missing')
})

await step('Arabic switch flips the layout to RTL', async () => {
  await page.click('.topbar .btn'); await page.waitForTimeout(400)
  const dir = await page.evaluate(() => document.documentElement.dir)
  await page.click('.topbar .btn')
  expect(dir === 'rtl', `dir=${dir}`); return 'dir=rtl, then back to ltr'
})

await step('Citizen signs in with the national digital ID', async () => {
  await go('dashboard'); await page.locator('main button.btn.primary').first().click()
  await page.waitForURL(/national-id-mock/, { timeout: 20000 })
  await page.fill('#username', NATIONAL_ID); await page.fill('#password', env.DEV_CUSTOMER_PASSWORD); await page.click('#kc-login')
  await page.waitForURL(new RegExp(BASE.replace(/[/.:]/g, '\\$&')), { timeout: 20000 }); await page.waitForTimeout(2500)
  const file = await page.locator('.kpi .v').first().textContent()
  expect(/\d{6,}/.test(file ?? ''), 'no traffic file on the dashboard'); return `file ${file}`
})

await step('Dashboard shows violations, points gauge, transactions and inbox', async () => {
  expect(await page.locator('.gauge').count() === 1, 'no gauge')
  const rows = await page.locator('.card .row').count()
  expect(rows > 0, 'no rows'); return `${rows} rows`
})

await step('Pay all → checkout → receipt → duplicate callback (UAT-4)', async () => {
  const pay = page.locator('.kpi.violet button')
  if (await pay.isDisabled()) return 'nothing open to pay (run demo-seed.sh to re-arm)'
  await pay.click(); await page.waitForURL(/mock-gateway/, { timeout: 20000 })
  await page.click('button[type=submit]'); await page.waitForURL(/#\/receipt/, { timeout: 20000 }); await page.waitForTimeout(1000)
  const receipt = await page.locator('.kv dd').first().textContent()
  await page.locator('.receipt .btn:not(.primary)').click(); await page.waitForTimeout(1500)
  const dup = await page.locator('.banner.info').textContent()
  expect(dup?.includes('duplicate: true'), 'no duplicate answer'); return `receipt ${receipt}, retry → duplicate`
})

await step('Control room records a radar violation', async () => {
  await go('control')
  if (await page.locator('input[type=password]').count()) { await page.fill('input[type=password]', env.DEV_MAKER_PASSWORD); await page.click('form button.primary') }
  await page.waitForTimeout(3000)
  await page.locator('.btn.shot').first().click(); await page.waitForTimeout(2500)
  const feed = await page.locator('.feed-row').first().textContent()
  expect(/recorded|سُجّلت/.test(feed ?? ''), `feed: ${feed}`); return feed?.slice(8, 90)
})

await step('Module tour: every story runs', async () => {
  await go('modules'); await page.waitForTimeout(2500)
  const tabs = await page.locator('.tour-tab:not(.future)').count(); const out = []
  for (let t = 0; t < tabs; t++) {
    await page.locator('.tour-tab:not(.future)').nth(t).click(); await page.waitForTimeout(300)
    const actions = await page.locator('.tour-body > div > .btn').count()
    for (let a = 0; a < actions; a++) {
      await page.locator('.tour-body > div > .btn').nth(a).click(); await page.waitForTimeout(800)
      await page.waitForFunction(() => [...document.querySelectorAll('.tour-body > div > .btn')].every((b) => !b.disabled), null, { timeout: 90000 })
      out.push(await page.locator('.timeline li.fail').count() ? 'x' : '✓')
    }
  }
  expect(!out.includes('x'), `failed stories: ${out.join('')}`); return `${out.length} stories ${out.join('')}`
})

await step('Federated search finds the demo file', async () => {
  await page.fill('.searchbar input', NATIONAL_ID); await page.click('.searchbar .btn')
  await page.waitForSelector('.searchbar + .card', { timeout: 20000 })
  expect(await page.locator('.searchbar + .card .row').count() > 0, 'no hits')
})

await step('Analytics: report charts and an intact audit chain', async () => {
  await go('analytics'); await page.waitForTimeout(9000)
  const chain = await page.locator('.chain b').textContent()
  expect(/intact|سليمة/i.test(chain ?? ''), `chain: ${chain}`); return chain
})

await step('Assistant answers from the customer data and can be stopped', async () => {
  await go('assistant'); await page.waitForTimeout(800)
  await page.locator('.chips .chip').first().click(); await page.waitForTimeout(1800)
  const tools = await page.locator('.bubble .tools').count()
  await page.click('.chat-head .btn'); const stopped = await page.locator('.chat-input input').isDisabled(); await page.click('.chat-head .btn')
  expect(tools > 0 && stopped, `tools=${tools} stopped=${stopped}`); return 'tool call shown; emergency stop works'
})

await step('Platform: all services up', async () => {
  await go('platform'); await page.waitForTimeout(4000)
  const up = await page.locator('.card h3 .pill').first().textContent()
  expect(/27\/27/.test(up ?? ''), `services: ${up}`); return up
})

await step('QA & security: live checks pass', async () => {
  await go('qa')
  for (const id of [0, 1, 2, 3, 4, 5, 6, 8]) await page.locator('.qa-row:not(.head) .btn').nth(id).click() // burst (7) skipped
  await page.waitForTimeout(6000)
  const ok = await page.locator('.qa-res.ok').count(); const bad = await page.locator('.qa-res.bad').count()
  expect(bad === 0 && ok === 8, `${ok} ok, ${bad} failed`); return `${ok}/8 (burst check left for the live demo)`
})

await step('API explorer loads the specs', async () => {
  await page.goto(`${BASE}/api.html`); await page.waitForTimeout(4000)
  expect(await page.locator('.opblock').count() > 5, 'no operations'); return `${await page.locator('.opblock').count()} operations shown`
})

await browser.close()
const failed = results.filter((r) => r[0] === 'FAIL')
console.log(`\n${results.length - failed.length}/${results.length} steps passed${pageErrors.length ? `; page errors: ${pageErrors.length}` : ''}; screenshots in demo/portal/e2e/out/`)
process.exit(failed.length ? 1 : 0)
