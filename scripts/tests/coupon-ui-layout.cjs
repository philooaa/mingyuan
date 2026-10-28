// Optional visual layout check: run with Playwright available in NODE_PATH.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { chromium } = require('playwright');
const root = path.resolve(__dirname, '../../WeChatMiniApp');

function styles(file) {
  return fs.readFileSync(file, 'utf8').replace(/@import\s+"([^"]+)";/g,
    (_, name) => styles(path.resolve(path.dirname(file), name)));
}

(async () => {
  const browser = await chromium.launch({ executablePath: process.env.CHROME_PATH || '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome', headless: true });
  try {
    const source = fs.readFileSync(path.join(root, 'pages/admin-coupon-section/admin-coupon-section.wxml'), 'utf8');
    const form = source.slice(source.indexOf('<view class="shade" wx:if="{{templateOpen}}">'), source.indexOf('<view class="shade" wx:if="{{grantOpen}}">'));
    const markup = form.replace(/{{[\s\S]*?}}/g, '').replace(/<(\/?)view\b/g, '<$1div')
      .replace(/<(\/?)block\b/g, '<$1div').replace(/<(\/?)picker\b/g, '<$1div')
      .replace(/<(\/?)checkbox-group\b/g, '<$1div').replace(/<checkbox\b/g, '<input type="checkbox"')
      .replace(/<textarea\b([^>]*?)\/>/g, '<textarea$1></textarea>');
    for (const [width, height] of [[390, 844], [320, 568]]) {
      const page = await browser.newPage({ viewport: { width, height } });
      const css = (styles(path.join(root, 'app.wxss')) + styles(path.join(root, 'styles/coupon-management.wxss')))
        .replace(/([\d.]+)rpx/g, (_, n) => `${Number(n) * width / 750}px`);
      await page.setContent(`<style>body{margin:0}scroll-view{display:block;overflow-y:auto}input{height:1.4rem}textarea{display:block}${css}</style>${markup}`);
      const fields = await page.locator('input:not([type="checkbox"])').evaluateAll(nodes => nodes.map(node => {
        const s = getComputedStyle(node);
        return { available: node.clientHeight - parseFloat(s.paddingTop) - parseFloat(s.paddingBottom), font: parseFloat(s.fontSize) };
      }));
      assert.ok(fields.length >= 3, 'render the actual template form fields');
      assert.ok(fields.every(f => f.available >= f.font * 1.2), `${width}px: input text must fit without vertical clipping: ${JSON.stringify(fields)}`);
      const buttons = page.locator('button[bindtap="saveCoupon"]');
      const scroll = page.locator('scroll-view');
      const before = await buttons.boundingBox();
      assert.ok(before.y >= 0 && before.y + before.height <= height, `${width}px: save action must remain visible before scrolling`);
      const sizes = await scroll.evaluate(node => ({ total: node.scrollHeight, visible: node.clientHeight }));
      assert.ok(sizes.total > sizes.visible && sizes.visible > 0, 'long form must scroll inside the sheet');
      await scroll.evaluate(node => { node.scrollTop = node.scrollHeight; });
      const after = await buttons.boundingBox();
      assert.ok(Math.abs(after.y - before.y) < 1, 'save action must stay in place while form scrolls');
      assert.ok(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth), 'no horizontal overflow');
      console.log(`PASS coupon UI ${width}x${height}: readable inputs, scrolling form, visible stationary save action`);
      await page.close();
    }
  } finally { await browser.close(); }
})().catch(error => { console.error(error); process.exitCode = 1; });
