// Layout smoke check; native Android/iOS rendering still needs a device preview.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { chromium } = require('playwright');
const root = path.resolve(__dirname, '../../WeChatMiniApp');
function styles(file) {
  return fs.readFileSync(file, 'utf8').replace(/@import\s+"([^"]+)";/g,
    (_, name) => styles(path.resolve(path.dirname(file), name)));
}
const cases = [
  ['pages/teacher-wifi-checkin/teacher-wifi-checkin.wxss', '<input class="wifi-manual-input" placeholder="手动输入WiFi名称">'],
  ['pages/admin-enrollment/admin-enrollment.wxss', '<div class="card"><div class="search"><input placeholder="学生姓名 / 家长手机号"><button>搜索</button></div></div>'],
  ['pages/admin-enrollment-list/admin-enrollment-list.wxss', '<div class="card"><div class="search"><input value="测试学生"><button>搜索</button></div></div>'],
  ['pages/teacher-students/teacher-students.wxss', '<div class="search"><input placeholder="搜索学生姓名"><button>搜索</button></div>'],
  ['pages/sign-review/sign-review.wxss', '<div class="course-search"><input value="测试班级"><button>搜索</button></div><div class="overview-student-search"><input value="测试学生"></div>'],
  ['styles/referral.wxss', '<div class="filters"><input placeholder="手机号"></div>'],
  ['styles/coupon-management.wxss', '<div class="filters"><input value="测试家长"></div>'],
  ['utils/material-shop.wxss', '<input placeholder="资料名称">'],
  ['pages/teacher-homework-create/teacher-homework-create.wxss', '<div class="card"><input placeholder="作业标题"></div>'],
  ['pages/teacher-homework-review-detail/teacher-homework-review-detail.wxss', '<div class="card"><input value="100"></div>'],
  ['pages/homework-detail/homework-detail.wxss', '<div class="card"><input class="input" placeholder="请输入答案"></div>'],
];
(async () => {
  const browser = await chromium.launch({ executablePath: '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome', headless: true });
  try {
    for (const width of [320, 390]) {
      const page = await browser.newPage({ viewport: { width, height: 844 } });
      for (const [file, markup] of cases) {
        const css = (styles(path.join(root, 'app.wxss')) + styles(path.join(root, file)))
          .replace(/([\d.]+)rpx/g, (_, n) => `${Number(n) * width / 750}px`);
        // WeChat's default native input height, before page styles apply.
        await page.setContent(`<style>body{margin:0}input{height:1.4rem;font-size:16px;min-width:0}button{flex-shrink:0}${css}</style><div class="page">${markup}</div>`);
        const fields = await page.locator('input').evaluateAll(nodes => nodes.map(node => {
          const s = getComputedStyle(node);
          return { available: node.clientHeight - parseFloat(s.paddingTop) - parseFloat(s.paddingBottom), font: parseFloat(s.fontSize) };
        }));
        assert.ok(fields.every(f => f.available >= f.font * 1.2), `${width}px ${file}: clipped text ${JSON.stringify(fields)}`);
        assert.ok(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth), `${file}: horizontal overflow`);
      }
      console.log(`PASS ${cases.length} input layouts at ${width}px`);
      await page.close();
    }
  } finally { await browser.close(); }
})().catch(error => { console.error(error); process.exitCode = 1; });
