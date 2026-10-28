const childContext = require('../../utils/child-context')
const { request } = require('../../utils/request')

function valueOf(item, names, fallback = '') {
  for (const name of names) if (item[name] !== undefined && item[name] !== null) return item[name]
  return fallback
}

const statusTabs = [
  { key: 'all', name: '全部' },
  { key: 'todo', name: '待提交' },
  { key: 'review', name: '待批阅' },
  { key: 'done', name: '已批阅' }
]

Page(childContext.page({
  data: { list: [], shown: [], groups: [], classTabs: [], activeClass: 'all', statusTabs, activeStatus: 'all', loading: false },
  onLoad(options={}){this.accountId=options.accountId?String(options.accountId):'';this._token=wx.getStorageSync('token');this._alive=true;if(options.status==='todo')this.setData({activeStatus:'todo'})},
  onShow() {this._visible=true;this.loadList()},
  onHide(){this._visible=false;this._version=(this._version||0)+1},
  onUnload(){this.onHide();this._alive=false},
  current(){return this._alive!==false&&this._visible!==false&&(!this._token||this._token===wx.getStorageSync('token'))},
  onPullDownRefresh() { this.loadList().finally(() => wx.stopPullDownRefresh()) },
  async loadList() {
    if(!this.current()){this.setData({list:[],shown:[],groups:[]});return}
    const version=this._version=(this._version||0)+1
    this.setData({ loading: true })
    try {
      const res = await request({ url: '/miniapp/parent/homework/list',...(this.accountId?{data:{accountId:this.accountId}}:{}) })
      if(!this.current()||version!==this._version)return
      const list = (res.data || []).map(item => {
        const submissionCount = Number(valueOf(item, ['submissionCount'], 0))
        const submissionStatus = String(valueOf(item, ['submissionStatus'], ''))
        const finalScore = valueOf(item, ['finalScore'], null)
        let category = 'todo'
        let statusText = submissionCount ? '已提交' : '待提交'
        if (submissionCount) {
          if (submissionStatus === '2') { category = 'done'; statusText = '已批阅' }
          else { category = 'review'; statusText = '待批阅' }
        } else if (String(valueOf(item, ['status'], '')) === '2') {
          statusText = '已关闭'; category='closed'
        }
        if(!submissionCount&&item.deadline&&new Date(String(item.deadline).replace(' ','T')).getTime()<Date.now()){category='closed';statusText='已截止'}
        const classId = String(valueOf(item, ['matchedScheduleId'], ''))
        return {
          ...item,
          homeworkId: valueOf(item, ['homeworkId', 'id']),
          // 同一作业发布到多个班级时按班级逐行返回，用组合键避免重复 key
          rowKey: `${valueOf(item, ['homeworkId', 'id'])}_${valueOf(item,['enrollmentId'],classId)}`,
          classId,
          className: valueOf(item, ['matchedScheduleName', 'scheduleName'], '未命名班级'),
          titleText: valueOf(item, ['title', 'homeworkTitle'], '未命名作业'),
          subjectText: valueOf(item, ['subjectName', 'subject'], '未设置'),
          teacherText: valueOf(item, ['teacherName', 'teacher'], '未设置'),
          deadlineText: valueOf(item, ['deadline', 'endTime'], '未设置'),
          category,
          statusText,
          scoreText: category === 'done' && finalScore !== null && finalScore !== '' ? `${finalScore} 分` : ''
        }
      })
      const seen = {}
      const classTabs = [{ key: 'all', name: '全部班级' }]
      list.forEach(item => { if (item.classId && !seen[item.classId]) { seen[item.classId] = true; classTabs.push({ key: item.classId, name: item.className }) } })
      const activeClass = classTabs.some(row => row.key === this.data.activeClass) ? this.data.activeClass : 'all'
      this.setData({ list, classTabs, activeClass })
      this.applyFilter()
    } catch (e) {if(this.current()&&version===this._version){this.setData({list:[],shown:[],groups:[]});wx.showToast({title:e.message||'加载失败',icon:'none'})}} finally {if(this.current()&&version===this._version)this.setData({loading:false})}
  },
  applyFilter() {
    const { list, activeClass, activeStatus } = this.data
    let shown = activeClass === 'all' ? list : list.filter(item => item.classId === activeClass)
    if (activeStatus !== 'all') shown = shown.filter(item => item.category === activeStatus)
    const grouped = new Map()
    shown.forEach(item => {
      const id = item.classId || 'unmatched'
      if (!grouped.has(id)) grouped.set(id, { classId: id, name: item.className || '未命名班级', items: [] })
      grouped.get(id).items.push(item)
    })
    const groups = Array.from(grouped.values())
    const nameCount = new Map()
    groups.forEach(g => nameCount.set(g.name, (nameCount.get(g.name) || 0) + 1))
    groups.forEach(g => { g.displayName = nameCount.get(g.name) > 1 ? `${g.name}（ID:${g.classId}）` : g.name })
    this.setData({ shown, groups })
  },
  switchClass(e) { this.setData({ activeClass: e.currentTarget.dataset.key }); this.applyFilter() },
  switchStatus(e) { this.setData({ activeStatus: e.currentTarget.dataset.key }); this.applyFilter() },
  openDetail(e) {
    if(!this.current())return
    const item = this.data.list.find(row => row.rowKey === e.currentTarget.dataset.key) || {}
    const query = `id=${item.homeworkId}&scheduleId=${item.classId || ''}${item.enrollmentId?'&enrollmentId='+encodeURIComponent(String(item.enrollmentId)):''}${this.accountId?'&accountId='+encodeURIComponent(this.accountId):''}`
    wx.navigateTo({ url: `/pages/homework-detail/homework-detail?${query}` })
  }
}, {route:'/pages/homework/homework',detail:false}))
