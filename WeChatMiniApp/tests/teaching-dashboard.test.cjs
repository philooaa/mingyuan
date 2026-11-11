const test=require('node:test'),assert=require('node:assert/strict'),fs=require('fs'),vm=require('vm');
test('教学看板按季度课程时段筛选并按课程累加教师学生数',async()=>{
 let page;const wx={showToast(){}};const rows=[{teacherId:'1',teacherName:'王老师',scheduleId:'1',courseClassName:'数学',timeSlot:'周六',courseYear:2026,termName:'秋季',studentCount:2},{teacherId:'1',teacherName:'王老师',scheduleId:'2',courseClassName:'英语',timeSlot:'周日',courseYear:2026,termName:'秋季',studentCount:1}];
 const request=async()=>({data:{weekStart:'2026-11-09',weekEnd:'2026-11-15',lessons:[{consumptionCount:'1.5'}],refunds:[{approvedAmount:'50.00'}],teachers:rows}});
 vm.runInNewContext(fs.readFileSync('WeChatMiniApp/pages/admin-dashboard/admin-dashboard.js','utf8'),{Page:p=>page=p,wx,require:id=>id.includes('request')?{request}:{selected:()=> 'admin',has:()=>true,can:()=>true}});
 page.data=structuredClone(page.data);page.setData=v=>Object.assign(page.data,v);await page.load();assert.equal(page.data.total,1.5);assert.equal(page.data.refundTotal,'50.00');assert.equal(page.data.teachers[0].count,3);page.data.courseIndex=page.data.courses.indexOf('数学');page.filter();assert.equal(page.data.teachers[0].count,2);page.data.timeIndex=page.data.times.indexOf('周日');page.filter();assert.equal(page.data.teachers.length,0);
});
test('看板菜单进入与返回保持层级，历史统计使用独立入口',async()=>{
 let page;const calls=[];const request=async o=>{calls.push(o);return {data:[]}};
 vm.runInNewContext(fs.readFileSync('WeChatMiniApp/pages/admin-dashboard/admin-dashboard.js','utf8'),{Page:p=>page=p,wx:{showToast(){}},require:id=>id.includes('request')?{request}:{}});
 page.data=structuredClone(page.data);page.setData=v=>Object.assign(page.data,v);
 page.openSection({currentTarget:{dataset:{section:'finance'}}});assert.equal(page.data.section,'finance');
 page.openSection({currentTarget:{dataset:{section:'finance-months'}}});page.goBack();assert.equal(page.data.section,'finance');page.goBack();assert.equal(page.data.section,'home');
 page.openSection({currentTarget:{dataset:{section:'history'}}});await new Promise(resolve=>setImmediate(resolve));assert.equal(page.data.section,'history');assert.equal(calls[0].url,'/miniapp/admin/teaching-dashboard/weekly-history');page.goBack();assert.equal(page.data.section,'home');
});
test('财务日期筛选精确到天并拒绝倒置区间',async()=>{
 let page;const calls=[],toasts=[];const request=async o=>{calls.push(o);return {data:{summary:{}}}};
 vm.runInNewContext(fs.readFileSync('WeChatMiniApp/pages/admin-dashboard/admin-dashboard.js','utf8'),{Page:p=>page=p,wx:{showToast:o=>toasts.push(o.title)},require:id=>id.includes('request')?{request}:{}});
 page.data=structuredClone(page.data);page.setData=v=>Object.assign(page.data,v);page.setData({beginDate:'2026-11-01',endDate:'2026-11-30'});
 page.financeDate({currentTarget:{dataset:{key:'beginDate'}},detail:{value:'2026-11-05'}});await new Promise(resolve=>setImmediate(resolve));assert.equal(calls[0].url,'/miniapp/admin/finance-period');assert.equal(calls[0].data.beginDate,'2026-11-05');assert.equal(calls[0].data.endDate,'2026-11-30');
 page.financeDate({currentTarget:{dataset:{key:'endDate'}},detail:{value:'2026-11-01'}});assert.equal(page.data.endDate,'2026-11-30');assert.equal(calls.length,1);assert.equal(toasts.length,1);
});
test('新增成本选择网页同一类型字典并传摘要、明细和日期，保存后刷新',async()=>{
 let page;const calls=[],dialogs=[{confirm:true,content:'房租测试'},{confirm:true,content:'11月房租'},{confirm:true,content:'120.50'},{confirm:true}],toast=[];
 const wx={showToast:o=>toast.push(o.title),showModal:o=>o.success(dialogs.shift()),showActionSheet:o=>o.success({tapIndex:0})};
 const request=async o=>{calls.push(o);if(o.url.endsWith('expense-types'))return {data:[{dictLabel:'房租',dictValue:'rent'}]};if(o.url.endsWith('/expenses'))return {data:[]};if(o.method==='POST')return {data:{}};return {data:{summary:{}}}};
 vm.runInNewContext(fs.readFileSync('WeChatMiniApp/pages/admin-dashboard/admin-dashboard.js','utf8'),{Page:p=>page=p,wx,require:id=>id.includes('request')?{request}:{}});page.data=structuredClone(page.data);page.setData=v=>Object.assign(page.data,v);page.data.section='expenses';page.data.beginDate='2026-11-01';page.data.endDate='2026-11-11';await page.addExpense();const saved=calls.find(c=>c.method==='POST');assert.equal(saved.url,'/miniapp/admin/finance-period/expense');assert.equal(saved.data.expenseType,'rent');assert.equal(saved.data.detail,'11月房租');assert.equal(saved.data.expenseDate,'2026-11-11');assert.ok(calls.some(c=>c.url.endsWith('/expenses')));assert.ok(toast.includes('已保存'));
});
test('新增成本允许摘要和明细留空',async()=>{
 let page;const calls=[],dialogs=[{confirm:true,content:''},{confirm:true,content:''},{confirm:true,content:'10'},{confirm:true}];
 const wx={showToast(){},showModal:o=>o.success(dialogs.shift()),showActionSheet:o=>o.success({tapIndex:0})};
 const request=async o=>{calls.push(o);return o.url.endsWith('expense-types')?{data:[{dictLabel:'其他',dictValue:'other'}]}:o.url.endsWith('/expenses')?{data:[]}:{data:{summary:{}}}};
 vm.runInNewContext(fs.readFileSync('WeChatMiniApp/pages/admin-dashboard/admin-dashboard.js','utf8'),{Page:p=>page=p,wx,require:id=>id.includes('request')?{request}:{}});page.data=structuredClone(page.data);page.setData=v=>Object.assign(page.data,v);page.data.section='expenses';page.data.beginDate='2026-11-01';page.data.endDate='2026-11-11';await page.addExpense();const saved=calls.find(c=>c.method==='POST');assert.ok(saved);assert.equal(saved.data.title,'');assert.equal(saved.data.detail,'');assert.equal(saved.data.amount,'10');
});
