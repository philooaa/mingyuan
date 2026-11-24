const fs = require('node:fs'), path = require('node:path'), http = require('node:http'), assert = require('node:assert/strict')
const { chromium } = require(process.env.PLAYWRIGHT_MODULE || path.join(process.env.USERPROFILE, '.cache/codex-runtimes/codex-primary-runtime/dependencies/node/node_modules/playwright'))
const root = path.resolve(__dirname, '../..'), ui = path.join(root, 'RuoYi-Vue-master/ruoyi-ui')
function component(file, name) {
  const source = fs.readFileSync(path.join(ui, 'src', file), 'utf8')
  const template = source.match(/<template>([\s\S]*?)<\/template>\s*<script>/)[1]
  const script = source.match(/<script>([\s\S]*?)<\/script>/)[1].replace(/^import .*$/mg, '').replace('export default', 'window.' + name + ' =')
  return script + ';window.' + name + '.template=' + JSON.stringify(template) + ';'
}
const html = `<!doctype html><meta charset="utf-8"><link rel="stylesheet" href="/element.css"><div id="app"><rule-manager ref="manager"></rule-manager><el-form><rule-picker :form="form"></rule-picker></el-form></div>
<script src="/vue.js"></script><script src="/element.js"></script><script>
window.saved=[];window.nextId=1;
async function listRules(p){return {data:saved.filter(r=>!p||!p.enabledOnly||r.status==='0').map(r=>({...r,dateCount:r.dates.length}))}}
async function getRule(id){return {data:JSON.parse(JSON.stringify(saved.find(r=>String(r.ruleId)===String(id))))}}
async function createRule(data){saved.push({...JSON.parse(JSON.stringify(data)),ruleId:String(nextId++),version:1});return {data:nextId-1}}
async function updateRule(id,data){const i=saved.findIndex(r=>String(r.ruleId)===String(id));saved[i]={...JSON.parse(JSON.stringify(data)),version:saved[i].version+1};return {}}
Vue.prototype.$modal={msgError:m=>{throw new Error(m)},msgSuccess:()=>{}};
${component('views/system/scheduleRule/index.vue', 'RuleManager')}
${component('components/ScheduleRulePicker/index.vue', 'RulePicker')}
window.app=new Vue({el:'#app',components:{RuleManager,RulePicker},data:{form:{classMode:'1',classPattern:'CUSTOM_DATES',customDates:[],requestedLessonCount:10}}});
</script>`
const assets = {'/vue.js':'vue/dist/vue.js','/element.js':'element-ui/lib/index.js','/element.css':'element-ui/lib/theme-chalk/index.css'}
;(async () => {
  const server = http.createServer((req,res) => { if(assets[req.url]){res.setHeader('Content-Type',req.url.endsWith('.css')?'text/css':'text/javascript');res.end(fs.readFileSync(path.join(ui,'node_modules',assets[req.url])))}else{res.setHeader('Content-Type','text/html; charset=utf-8');res.end(html)} })
  await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve))
  const browser = await chromium.launch({channel:'msedge',headless:true})
  try {
    const page = await browser.newPage({viewport:{width:1200,height:900}}), errors=[]
    page.on('pageerror',e=>errors.push(e.message))
    await page.goto(`http://127.0.0.1:${server.address().port}`)
    await page.getByRole('button',{name:'新增规则',exact:true}).click()
    await page.locator('.el-dialog input').first().fill('跨年四讲')
    await page.evaluate(()=>{app.$refs.manager.form.dates=['2026-12-26','2026-12-28','2027-01-01','2027-01-02']})
    await page.getByRole('button',{name:'保存',exact:true}).click()
    await page.getByRole('cell',{name:'跨年四讲',exact:true}).waitFor()
    await page.evaluate(()=>app.$children[1].$children.find(c=>c.$options.name==='ScheduleRulePicker').load())
    await page.locator('.el-form .el-select').last().click()
    await page.getByText('跨年四讲',{exact:true}).last().click()
    await page.waitForFunction(()=>app.form.customDates.length===4,{},{timeout:3000}).catch(async e=>{console.log(await page.evaluate(()=>({form:app.form,saved,errors:document.querySelectorAll('.el-message').length,text:document.body.innerText})));throw e})
    const form=await page.evaluate(()=>JSON.parse(JSON.stringify(app.form)))
    assert.equal(form.startDate,'2026-12-26');assert.equal(form.endDate,'2027-01-02');assert.equal(form.requestedLessonCount,10);assert.equal(form.applyScheduleRule,true)
    await page.getByRole('button',{name:'查看／编辑',exact:true}).click()
    await page.evaluate(()=>{app.$refs.manager.form.dates=['2027-02-01'];app.$refs.manager.form.status='1'})
    await page.getByRole('button',{name:'保存',exact:true}).click()
    assert.deepEqual(await page.evaluate(()=>app.form.customDates),form.customDates)
    assert.deepEqual(errors,[])
    console.log('PASS: rule maintenance, selecting a rule, automatic bounds, independent billing count, and immutable course preview after rule edits')
  } finally {await browser.close();await new Promise(resolve=>server.close(resolve))}
})().catch(e=>{console.error(e);process.exitCode=1})
