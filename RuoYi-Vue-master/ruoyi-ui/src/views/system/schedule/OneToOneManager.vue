<template>
  <el-dialog title="一对一管理" :visible.sync="visible" width="1120px" top="4vh" append-to-body :close-on-click-modal="false" :before-close="close">
    <el-alert title="管理员统一代办，操作记录保留当前管理员身份；与小程序共用课程、预约和课次数据。" type="info" :closable="false" show-icon class="mb16" />
    <el-form inline size="small" @submit.native.prevent>
      <el-form-item label="教师">
        <el-select v-model="teacherId" clearable filterable placeholder="全部教师（发布前请选择）" :disabled="busy" @change="changeTeacher">
          <el-option v-for="t in dims.teachers" :key="t.userId" :value="t.userId" :label="`${t.nickName}（${t.userName}）`" />
        </el-select>
      </el-form-item>
      <el-form-item><el-button icon="el-icon-refresh" :disabled="busy" :loading="loading" @click="refresh">刷新</el-button></el-form-item>
      <span v-if="teacherId && !loading && !error" class="profile">{{ profile.teacherSubject || '尚未配置学科' }} · {{ { elite: '精英教师', senior: '资深教师' }[profile.teacherLevel] || '尚未配置等级' }}</span>
    </el-form>
    <el-alert v-if="error" :title="error" type="error" :closable="false" show-icon class="mb16" />
    <el-tabs v-model="tab" :before-leave="allowTabChange">
      <el-tab-pane label="开放时段与发布" name="slots">
        <el-form inline size="small" @submit.native.prevent>
          <el-form-item label="查看日期"><el-date-picker v-model="date" type="date" value-format="yyyy-MM-dd" :clearable="false" :disabled="busy" @change="refresh" /></el-form-item>
          <el-form-item><el-button :disabled="busy" @click="moveWeek(-7)">上一周</el-button><el-button :disabled="busy" @click="moveWeek(7)">下一周</el-button></el-form-item>
          <el-button v-if="editing" type="text" :disabled="busy" @click="cancelEdit">退出调整，改为新建</el-button>
        </el-form>
        <el-alert v-if="!teacherId" title="先选择教师，再查看空闲时段。教师学科和等级从用户管理中的授课配置读取。" type="info" :closable="false" />
        <el-alert v-if="editing" :title="'正在调整：' + editing.courseClassName + '；请重新选择具体时段和教室。已有报名或历史不能直接改期。'" type="warning" :closable="false" class="mb16" />
        <el-alert v-if="pendingCount" :title="`${pendingCount} 条排课日期待配置，未被猜测为空闲占用，请先核对历史排课。`" type="warning" :closable="false" class="mb16" />
        <el-table v-if="teacherId" v-loading="loading" :data="slotRows" border size="small" class="slot-table">
          <el-table-column prop="timeSlot" label="固定时段" width="120" />
          <el-table-column v-for="day in days" :key="day" :label="day.slice(5)" min-width="110">
            <template slot-scope="s">
              <el-button v-if="s.row[day]" size="mini" :type="isSelected(s.row[day]) ? 'primary' : ''" :disabled="busy || loading || !!error || !flag(s.row[day].canPublish)" @click="pickCell(s.row[day])">{{ flag(s.row[day].canPublish) ? '选择时段' : s.row[day].reason }}</el-button>
            </template>
          </el-table-column>
        </el-table>
        <el-form v-if="selectedCell" label-width="100px" size="small" class="publish-form" @submit.native.prevent>
          <h4>{{ selectedCell.date }} · {{ selectedCell.timeSlot }}</h4>
          <el-form-item label="年级" required><el-select v-model="form.gradeName" :disabled="busy" filterable><el-option v-for="g in dims.grades" :key="g.value" :value="g.value" :label="g.label" /></el-select></el-form-item>
          <el-form-item label="课程名称" required><el-input v-model="form.courseClassName" :disabled="busy" maxlength="100" placeholder="请输入课程名称" /></el-form-item>
          <el-form-item label="空闲教室" required><el-select v-model="form.classroomId" :disabled="busy" filterable><el-option v-for="r in selectedCell.classrooms" :key="r.classroomId" :value="r.classroomId" :label="`${r.campusName} · ${r.classroomName}`" /></el-select></el-form-item>
          <el-form-item><el-button type="primary" :loading="busy" :disabled="loading || !!error || !profile.teacherSubject" @click="save">{{ editing ? '保存调整' : '发布一节课程' }}</el-button></el-form-item>
        </el-form>
      </el-tab-pane>
      <el-tab-pane label="已发布课程" name="courses">
        <el-table v-loading="loading" :data="coursePage" size="small" max-height="480">
          <el-table-column prop="courseClassName" label="课程" min-width="170" />
          <el-table-column prop="teacherName" label="教师" width="100" />
          <el-table-column label="年级 / 学科" width="140"><template slot-scope="s">{{ s.row.gradeName }} / {{ s.row.subjectName }}</template></el-table-column>
          <el-table-column label="日期 / 时段" min-width="180"><template slot-scope="s">{{ s.row.startDate || '日期待核对' }}{{ s.row.endDate !== s.row.startDate ? ' 至 ' + s.row.endDate : '' }}<br>{{ s.row.timeSlot }}</template></el-table-column>
          <el-table-column label="状态" width="80"><template slot-scope="s">{{ s.row.status === '0' ? '已发布' : '已撤回' }}</template></el-table-column>
          <el-table-column label="操作" width="210"><template slot-scope="s">
            <el-button type="text" size="mini" :disabled="busy || loading || !!error" @click="viewBookings(s.row)">预约</el-button>
            <el-button type="text" size="mini" :disabled="busy || loading || !!error || !s.row.canEdit" @click="editCourse(s.row)">调整</el-button>
            <el-button type="text" size="mini" :disabled="busy || loading || !!error || !s.row.canWithdraw" @click="withdraw(s.row)">撤回</el-button>
          </template></el-table-column>
        </el-table>
        <pagination v-show="courses.length > 20" :total="courses.length" :page.sync="coursePageNum" :limit="20" :page-sizes="[20]" />
        <p class="help">有人报名时禁止调整、撤回。撤回只停用开放课程，保留预约和考勤历史。</p>
      </el-tab-pane>
      <el-tab-pane :label="'预约处理与课次记录（' + pendingBookings + ' 待处理）'" name="bookings">
        <el-form inline size="small" @submit.native.prevent>
          <el-form-item><el-input v-model="keyword" clearable placeholder="搜索学生、课程或电话" @input="bookingPageNum = 1" /></el-form-item>
          <el-form-item><el-checkbox v-model="pendingOnly" @change="bookingPageNum = 1">只看待处理</el-checkbox></el-form-item>
          <el-form-item v-if="scheduleId"><el-button :disabled="busy" @click="clearSchedule">清除课程限定</el-button></el-form-item>
        </el-form>
        <el-table v-loading="loading" :data="bookingPage" size="small" max-height="480">
          <el-table-column label="学员" min-width="130"><template slot-scope="s">{{ s.row.studentName }}<br><small>{{ s.row.contactPhone }}</small></template></el-table-column>
          <el-table-column label="课程 / 教师" min-width="170"><template slot-scope="s">{{ s.row.courseClassName }}<br>{{ s.row.teacherName }}</template></el-table-column>
          <el-table-column label="上课时间" width="150"><template slot-scope="s">{{ s.row.classDate }}<br>{{ s.row.startTime }}–{{ s.row.endTime }}</template></el-table-column>
          <el-table-column label="结算" width="110"><template slot-scope="s">{{ s.row.settlementMethod === 'P' ? `课包 ${s.row.lessonCredits} 次` : '单次线下收费' }}</template></el-table-column>
          <el-table-column label="状态" min-width="160"><template slot-scope="s"><el-tag size="mini">{{ status(s.row) }}</el-tag><p v-if="s.row.cancelRequestReason" class="help">取消原因：{{ s.row.cancelRequestReason }}</p></template></el-table-column>
          <el-table-column label="操作" width="240"><template slot-scope="s">
            <el-button v-for="action in actions(s.row)" :key="action" type="text" size="mini" :disabled="busy || loading || !!error" @click="act(s.row, action)">{{ actionLabel(action) }}</el-button>
            <el-button type="text" size="mini" :disabled="busy || loading || !!error" @click="history(s.row)">历史</el-button>
            <el-button v-if="s.row.settlementMethod !== 'P'" type="text" size="mini" v-hasPermi="['system:tuition:list']" :disabled="busy" @click="finance(s.row)">收费 / 退费</el-button>
          </template></el-table-column>
        </el-table>
        <pagination v-show="filteredBookings.length > 20" :total="filteredBookings.length" :page.sync="bookingPageNum" :limit="20" :page-sizes="[20]" />
        <p class="help">课包报名先暂占，取消或拒绝按原快照释放，完成后正式扣次。已收款预约的取消不代表退款，现金退费仍走原财务流程。历史无日期报名请在交易管理核对，不能猜测处理。</p>
      </el-tab-pane>
    </el-tabs>
    <div slot="footer"><el-button :disabled="busy" @click="close">关闭</el-button></div>
    <el-dialog title="预约操作历史" :visible.sync="historyOpen" width="760px" append-to-body>
      <el-table v-loading="historyLoading" :data="events" size="small"><el-table-column label="操作" width="145"><template slot-scope="s">{{ actionLabel(s.row.action) }}</template></el-table-column><el-table-column prop="actorName" label="实际操作账号" width="140" /><el-table-column prop="reason" label="原因" /><el-table-column prop="createTime" label="时间" width="165" /></el-table>
    </el-dialog>
  </el-dialog>
</template>

<script>
import { singleProfile, singleAvailability, singleCourses, singleBookings, publishSingle, updateSingle, withdrawSingle, decideSingle, completeSingle, singleHistory } from '@/api/system/oneToOne'
import { getToken } from '@/utils/auth'

export default {
  name: 'OneToOneManager',
  props: { dims: { type: Object, required: true } },
  data() {
    return { visible: false, tab: 'slots', teacherId: null, date: '', profile: {}, cells: [], courses: [], bookings: [], pendingCount: 0,
      loading: false, busy: false, error: '', version: 0, selectedCell: null, editing: null, form: { gradeName: '', courseClassName: '', classroomId: null },
      scheduleId: null, keyword: '', pendingOnly: false, coursePageNum: 1, bookingPageNum: 1, historyOpen: false, historyLoading: false, historyVersion: 0, events: [] }
  },
  computed: {
    days() { return [...new Set(this.cells.map(c => c.date))].sort() },
    slotRows() { const rows = []; for (const c of this.cells) { let row = rows.find(r => r.timeSlot === c.timeSlot); if (!row) { row = { timeSlot: c.timeSlot }; rows.push(row) } row[c.date] = c } return rows },
    coursePage() { return this.courses.slice((this.coursePageNum - 1) * 20, this.coursePageNum * 20) },
    pendingBookings() { return this.bookings.filter(b => this.actions(b).some(a => a !== 'COMPLETE')).length },
    filteredBookings() { const word = this.keyword.trim(); return this.bookings.filter(b => (!this.pendingOnly || this.actions(b).length) && (!word || [b.studentName, b.courseClassName, b.contactPhone].some(v => String(v || '').includes(word)))) },
    bookingPage() { return this.filteredBookings.slice((this.bookingPageNum - 1) * 20, this.bookingPageNum * 20) }
  },
  beforeDestroy() { this.version++; this.historyVersion++ },
  methods: {
    flag(value) { return value === true || value === 1 || value === '1' },
    today() { const d = new Date(); return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}` },
    async open(row = {}, tab = 'slots') {
      if (this.busy) return
      this.visible = true; this.tab = tab; this.teacherId = row.teacherId || null; this.date = row.startDate || this.today()
      this.editing = null; this.scheduleId = tab === 'bookings' ? row.scheduleId || null : null; this.keyword = ''; this.pendingOnly = false
      this.form = { gradeName: '', courseClassName: '', classroomId: null }; this.coursePageNum = 1; this.bookingPageNum = 1
      await this.refresh()
    },
    close() { if (this.busy) return; this.visible = false; this.version++; this.historyVersion++; this.historyOpen = false },
    allowTabChange() { return !this.busy },
    changeTeacher() {
      this.editing = null; this.selectedCell = null; this.form = { gradeName: '', courseClassName: '', classroomId: null }; this.scheduleId = null
      this.coursePageNum = 1; this.bookingPageNum = 1
      return this.refresh()
    },
    async refresh() {
      const version = ++this.version, session = getToken(), teacher = this.teacherId
      this.selectedCell = null; this.form.classroomId = null; this.cells = []; this.profile = {}; this.courses = []; this.bookings = []; this.pendingCount = 0; this.error = ''; this.loading = true
      try {
        const [courses, bookings, profile, available] = await Promise.all([singleCourses(teacher), singleBookings(teacher, this.scheduleId),
          teacher ? singleProfile(teacher) : Promise.resolve({ data: {} }), teacher ? singleAvailability(teacher, this.date, this.editing && this.editing.scheduleId) : Promise.resolve({ data: { cells: [] } })])
        if (!this.visible || version !== this.version || session !== getToken()) return
        this.courses = courses.data || []; this.bookings = bookings.data || []; this.profile = profile.data || {}; this.cells = available.data.cells || []; this.pendingCount = available.data.pendingScheduleCount || 0
      } catch (e) { if (version === this.version && this.visible) this.error = '加载失败，请刷新重试；不会把未加载的时段当作空闲。' }
      finally { if (version === this.version) this.loading = false }
    },
    moveWeek(days) { if (this.busy || !this.date) return; const d = new Date(this.date + 'T12:00:00'); d.setDate(d.getDate() + days); this.date = `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`; return this.refresh() },
    isSelected(c) { return this.selectedCell && this.selectedCell.date === c.date && this.selectedCell.timeSlot === c.timeSlot },
    pickCell(c) {
      if (this.busy || this.loading || this.error || !this.flag(c.canPublish)) return
      this.selectedCell = c; this.form.classroomId = null
    },
    async save() {
      if (this.busy || this.loading || this.error || !this.teacherId) return
      const c = this.selectedCell && this.cells.find(c => c.date === this.selectedCell.date && c.timeSlot === this.selectedCell.timeSlot)
      if (!c || !this.flag(c.canPublish) || !c.classrooms.some(r => String(r.classroomId) === String(this.form.classroomId))) { this.$message.warning('请重新选择空闲时段和教室'); return }
      if (!this.form.gradeName || !this.form.courseClassName.trim() || !this.profile.teacherSubject) { this.$message.warning('请填写年级、课程名称，并配置教师学科'); return }
      const data = { teacherId: this.teacherId, classroomId: this.form.classroomId, timeSlot: c.timeSlot, startDate: c.date, gradeName: this.form.gradeName, courseClassName: this.form.courseClassName.trim() }
      this.busy = true
      try {
        if (this.editing) await updateSingle(this.editing.scheduleId, data); else await publishSingle(data)
        this.$message.success(this.editing ? '调整成功' : '课程已发布，小程序可同步查看'); this.editing = null; this.tab = 'courses'; this.$emit('changed'); await this.refresh()
      } catch (e) { /* 统一请求拦截器提示错误，保留填写内容供核对 */ }
      finally { this.busy = false }
    },
    async editCourse(row) {
      if (this.busy || !row.canEdit) return
      this.teacherId = row.teacherId; this.editing = row; this.date = row.startDate || this.today(); this.tab = 'slots'
      this.form = { gradeName: row.gradeName, courseClassName: row.courseClassName, classroomId: null }; await this.refresh()
    },
    cancelEdit() { if (this.busy) return; this.editing = null; return this.refresh() },
    viewBookings(row) { if (this.busy) return; this.scheduleId = row.scheduleId; this.tab = 'bookings'; this.bookingPageNum = 1; return this.refresh() },
    clearSchedule() { this.scheduleId = null; this.bookingPageNum = 1; return this.refresh() },
    async withdraw(row) {
      if (this.busy || this.loading || this.error || !row.canWithdraw) return
      const session = getToken(), version = this.version
      this.busy = true
      try {
        await this.$confirm(`确认撤回「${row.courseClassName}」？该课程下全部日期将停止开放，历史记录保留；后台会重新核对是否有人报名。`, '撤回开放课程', { type: 'warning' })
        if (!this.visible || session !== getToken() || version !== this.version) { this.visible = false; return }
        await withdrawSingle(row.scheduleId); this.$message.success('已撤回'); this.$emit('changed'); await this.refresh()
      } catch (e) { /* 取消或服务端拒绝时不改变本地记录 */ } finally { this.busy = false }
    },
    actions(row) {
      if (this.flag(row.lessonCompleted)) return []
      if (this.flag(row.canProcess)) {
        if (String(row.cancelRequestStatus) === '1') return ['APPROVE_CANCEL', 'REJECT_CANCEL']
        if (String(row.enrollmentStatus) === '0') return ['CONFIRM', 'REJECT']
      }
      return this.flag(row.canComplete) ? ['COMPLETE'] : []
    },
    actionLabel(a) { return { SUBMIT: '提交预约', CONFIRM: '确认接课', REJECT: '拒绝预约', REQUEST_CANCEL: '申请取消', APPROVE_CANCEL: '同意取消', REJECT_CANCEL: '驳回取消', COMPLETE: '确认课程完成' }[a] || a },
    status(row) { return this.flag(row.lessonCompleted) ? '已完成' : ({ '0': '待确认', '1': '已确认', '2': '已取消', '3': '已拒绝' }[row.enrollmentStatus] || '待核对') + (String(row.cancelRequestStatus) === '1' ? ' · 取消待处理' : '') },
    async act(row, action) {
      if (this.busy || this.loading || this.error || !this.actions(row).includes(action)) return
      const session = getToken(), version = this.version
      this.busy = true
      try {
        let reason
        if (['REJECT', 'APPROVE_CANCEL', 'REJECT_CANCEL'].includes(action)) {
          const r = await this.$prompt('请填写处理原因（取消预约不代表现金退款）', this.actionLabel(action), { inputValidator: v => !!(v && v.trim() && v.trim().length <= 255) || '请填写1至255字原因' }); reason = r.value.trim()
        } else await this.$confirm(action === 'COMPLETE' ? '确认本次课程已结束且已登记到课？课包将按报名时的1或2次快照正式扣除，不重复扣次。' : '确认接下本次预约？这不会自动确认收费。', this.actionLabel(action), { type: 'warning' })
        if (!this.visible || session !== getToken() || version !== this.version) { this.visible = false; return }
        if (action === 'COMPLETE') await completeSingle(row.enrollmentId); else await decideSingle(row.enrollmentId, { action, ...(reason ? { reason } : {}) })
        this.$message.success('处理成功'); this.$emit('changed'); await this.refresh()
      } catch (e) { /* 取消不写入，后台状态冲突由统一拦截器提示 */ } finally { this.busy = false }
    },
    async history(row) {
      const version = ++this.historyVersion, session = getToken(); this.events = []; this.historyOpen = true; this.historyLoading = true
      try { const r = await singleHistory(row.enrollmentId); if (this.visible && this.historyOpen && version === this.historyVersion && session === getToken()) this.events = r.data || [] }
      finally { if (version === this.historyVersion) this.historyLoading = false }
    },
    finance(row) { this.close(); this.$router.push({ path: '/finance/tuition', query: { enrollmentId: String(row.enrollmentId) } }) }
  }
}
</script>

<style scoped>
.mb16 { margin-bottom: 16px; }
.profile { line-height: 32px; color: #175696; }
.slot-table .el-button { width: 100%; white-space: normal; line-height: 1.4; padding: 7px 3px; }
.publish-form { max-width: 600px; background: #f5f8fc; border-radius: 8px; padding: 16px; margin-top: 16px; }
.help { color: #75839a; font-size: 12px; line-height: 1.7; }
</style>
