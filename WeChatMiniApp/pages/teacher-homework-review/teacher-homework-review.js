const { request } = require('../../utils/request')
function valueOf(item, names, fallback = '') { for (const n of names) if (item && item[n] !== undefined && item[n] !== null) return item[n]; return fallback }
function safeDecode(value) { if (!value) return ''; try { return decodeURIComponent(value) } catch (e) { return value } }
Page({
  data: { id: null, title: '', scheduleId: '', scheduleName: '', emptyText: '暂无数据', groups: [], stats: { total: 0, submitted: 0, pending: 0, reviewed: 0, unreviewed: 0 }, loading: false },
  onLoad(q) { this.setData({ id: q.id, title: safeDecode(q.title), scheduleId: q.scheduleId || '', scheduleName: safeDecode(q.scheduleName) }); this.load() },
  async load() {
    this.setData({ loading: true })
    try {
      const [submissionRes, statsRes] = await Promise.all([
        request({ url: `/miniapp/teacher/homework/${this.data.id}/submissions` }),
        request({ url: `/miniapp/teacher/homework/${this.data.id}/submission-stats` })
      ])
      const statsRows = statsRes.data || []
      // 提交记录按 enrollmentId 建索引，便于合并到每个学生
      const byEnrollment = {}
      ;(submissionRes.data || []).forEach(item => {
        const eid = String(valueOf(item, ['enrollmentId'], ''))
        if (eid) byEnrollment[eid] = item
      })
      // 按班级分组：stats 已覆盖每个班级每位在读学生（含未提交）
      const groupMap = {}
      const order = []
      statsRows.forEach(row => {
        const sid = String(valueOf(row, ['scheduleId', 'schedule_id'], ''))
        const sname = valueOf(row, ['scheduleName', 'schedule_name'], '') || '未命名班级'
        if (!groupMap[sid]) { groupMap[sid] = { scheduleId: sid, name: sname, students: [], submitted: 0, pending: 0, reviewed: 0, unreviewed: 0 }; order.push(sid) }
        const g = groupMap[sid]
        const submitted = valueOf(row, ['submitted'], 0) === 1 || valueOf(row, ['submitted'], 0) === '1' || valueOf(row, ['submissionId', 'submission_id'], null) != null
        const sub = byEnrollment[String(valueOf(row, ['enrollmentId', 'enrollment_id'], ''))] || {}
        const submissionId = valueOf(row, ['submissionId', 'submission_id'], valueOf(sub, ['submissionId', 'id'], ''))
        const status = String(valueOf(row, ['submissionStatus', 'submission_status', 'status'], valueOf(sub, ['status'], '')))
        const reviewed = submitted && status === '2'
        const finalScore = valueOf(row, ['finalScore', 'final_score'], valueOf(sub, ['finalScore', 'final_score'], null))
        g.students.push({
          enrollmentId: valueOf(row, ['enrollmentId', 'enrollment_id'], ''),
          studentName: valueOf(row, ['studentName', 'student_name'], '') || '学生',
          submitted,
          submissionId: submitted ? submissionId : '',
          reviewed,
          submitTime: valueOf(sub, ['submitTime', 'submit_time'], ''),
          scoreText: submitted ? (reviewed ? (finalScore === null || finalScore === undefined || finalScore === '' ? '未批阅' : finalScore + ' 分') : '未批阅') : '未提交'
        })
        if (submitted) g.submitted++
        if (reviewed) g.reviewed++
      })
      // 按班级整理：已提交名单与未提交名单分开；从具体班级进入时只看本班
      const onlySchedule = this.data.scheduleId ? String(this.data.scheduleId) : ''
      const groups = order.map(sid => groupMap[sid]).filter(g => !onlySchedule || g.scheduleId === onlySchedule).map(g => {
        g.submittedStudents = g.students.filter(s => s.submitted)
        g.missing = g.students.filter(s => !s.submitted)
        g.unreviewed = g.submittedStudents.filter(s => !s.reviewed).length
        return g
      })
      const allStudents = groups.reduce((s, g) => s.concat(g.students), [])
      const total = allStudents.length
      const submitted = allStudents.filter(s => s.submitted).length
      const reviewed = allStudents.filter(s => s.reviewed).length
      this.setData({
        groups,
        emptyText: onlySchedule ? '本班暂无在读学员或提交' : '暂无数据',
        stats: { total, submitted, pending: total - submitted, reviewed, unreviewed: submitted - reviewed }
      })
    } catch (e) { wx.showToast({ title: e.message || '加载失败', icon: 'none' }) } finally { this.setData({ loading: false }) }
  },
  open(e) { if (!e.currentTarget.dataset.id) return; wx.navigateTo({ url: `/pages/teacher-homework-review-detail/teacher-homework-review-detail?id=${e.currentTarget.dataset.id}&homeworkId=${this.data.id}` }) }
})
