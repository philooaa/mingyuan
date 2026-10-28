<template>
  <el-dialog
    :title="form.mode==='edit' ? '编辑排课' : '新增排课'"
    :visible.sync="visible"
    :close-on-click-modal="false"
    width="660px"
    top="8vh"
    append-to-body>

    <el-form ref="form" :model="form" :rules="rules" label-width="100px" size="small" @submit.native.prevent>
      <!-- 归属学期 / 期次（由课表筛选条件决定，只读展示） -->
      <div class="dim-readonly">
        <span class="dim-label">归属：</span>
        <el-tag size="small">{{ form.termName || '—' }}</el-tag>
        <el-tag size="small" type="success">{{ form.periodName || '—' }}</el-tag>
        <span class="dim-tip">学期 / 期次 跟随课表筛选条件，如需调整请在课表页切换</span>
      </div>

      <el-row :gutter="16">
        <!-- ★ 教室 / 时段：可编辑，保存后课表上的课程移动到新位置 -->
        <el-col :span="12">
          <el-form-item label="教室" prop="classroomId">
            <el-select v-model="form.classroomId" placeholder="请选择教室" style="width:100%" filterable clearable
                       @change="onClassroomChange">
              <el-option-group v-for="(list, campus) in (dims.classrooms || {})" :key="campus" :label="campus">
                <el-option v-for="c in list" :key="c.classroomId"
                           :label="c.classroomName" :value="c.classroomId" />
              </el-option-group>
            </el-select>
          </el-form-item>
        </el-col>
        <el-col :span="12">
          <el-form-item label="时段" prop="timeSlot">
            <el-select v-model="form.timeSlot" placeholder="请选择时段" style="width:100%" clearable>
              <el-option v-for="t in timeSlotOptions" :key="t" :label="t" :value="t" />
            </el-select>
          </el-form-item>
        </el-col>

        <el-col :span="24">
          <div class="move-hint">
            <i class="el-icon-info"></i>
            修改「教室」或「时段」并保存后，该课程会移动到课表对应的新位置；若目标位置已有课程，将并存并显示红色感叹号冲突提示。
          </div>
        </el-col>

        <el-col :span="12">
          <el-form-item label="年级" prop="gradeName">
            <el-select v-model="form.gradeName" placeholder="请选择年级" style="width:100%" filterable>
              <el-option v-for="g in dims.grades" :key="g.value" :label="g.label" :value="g.value" />
            </el-select>
          </el-form-item>
        </el-col>
        <el-col :span="12">
          <el-form-item label="科目" prop="subjectName">
            <el-select v-model="form.subjectName" placeholder="请选择科目" style="width:100%" filterable>
              <el-option v-for="s in dims.subjects" :key="s.value" :label="s.label" :value="s.value" />
            </el-select>
          </el-form-item>
        </el-col>

        <el-col :span="12">
          <el-form-item label="教师" prop="teacherId">
            <el-select v-model="form.teacherId" placeholder="请选择教师" style="width:100%" filterable
                       :disabled="!dims.isAdmin && dims.isTeacher">
              <el-option v-for="t in dims.teachers" :key="t.userId" :label="teacherLabel(t)" :value="t.userId" />
            </el-select>
            <div v-if="!dims.isAdmin && dims.isTeacher" style="font-size:12px;color:#909399;line-height:1.4;margin-top:2px;">
              教师身份仅能为自己排课
            </div>
          </el-form-item>
        </el-col>
        <el-col :span="12">
          <el-form-item label="班型" prop="classType">
            <el-select v-model="form.classType" placeholder="请选择班型（可空）" style="width:100%" clearable>
              <el-option v-for="c in dims.classTypes" :key="c.value" :label="c.label" :value="c.value" />
            </el-select>
          </el-form-item>
        </el-col>

        <el-col :span="12">
          <el-form-item label="开始时间" prop="startTime">
            <el-time-picker v-model="form.startTime" value-format="HH:mm:ss" placeholder="开始时间" style="width:100%" />
          </el-form-item>
        </el-col>
        <el-col :span="12">
          <el-form-item label="结束时间" prop="endTime">
            <el-time-picker v-model="form.endTime" value-format="HH:mm:ss" placeholder="结束时间" style="width:100%" />
          </el-form-item>
        </el-col>

        <el-col :span="12">
          <el-form-item label="开课日期" prop="startDate">
            <el-date-picker v-model="form.startDate" type="date" value-format="yyyy-MM-dd" placeholder="开课日期" style="width:100%" />
          </el-form-item>
        </el-col>
        <el-col :span="12">
          <el-form-item label="结课日期" prop="endDate">
            <el-date-picker v-model="form.endDate" type="date" value-format="yyyy-MM-dd" placeholder="结课日期" style="width:100%" />
          </el-form-item>
        </el-col>

        <el-col :span="12">
          <el-form-item label="上课模式" prop="classPattern">
            <el-select v-model="form.classPattern" placeholder="请选择上课模式" style="width:100%">
              <el-option label="每周一次" value="WEEKLY" />
              <el-option label="上5天休1天" value="DAILY_5_1" />
            </el-select>
          </el-form-item>
        </el-col>
        <el-col :span="12">
          <el-form-item label="授课形式">
            <el-select v-model="form.classMode" :disabled="!form.scheduleId && dims.isTeacher && !dims.isAdmin" style="width:100%">
              <el-option label="班课" value="1" />
              <el-option label="一对一（请使用一对一管理）" value="2" :disabled="dims.isAdmin" />
            </el-select>
          </el-form-item>
        </el-col>
        <el-col :span="12">
          <el-form-item label="排课状态">
            <el-select v-model="form.status" style="width:100%">
              <el-option label="正常" value="0" />
              <el-option label="停用" value="1" />
            </el-select>
          </el-form-item>
        </el-col>
        <el-col :span="12">
          <el-form-item label="招生状态" prop="recruitStatus">
            <el-select v-model="form.recruitStatus" style="width:100%">
              <el-option label="可报名" value="0" />
              <el-option label="停招" value="1" />
              <el-option label="满班" value="2" />
            </el-select>
          </el-form-item>
        </el-col>
        <el-col :span="12">
          <el-form-item label="已报名数" prop="enrolledCount">
            <el-input :value="form.enrolledCount" disabled placeholder="由报名业务维护" />
          </el-form-item>
        </el-col>

        <el-col :span="24">
          <el-form-item label="课程班名称" prop="courseClassName">
            <el-input v-model="form.courseClassName" placeholder="如：五年级数学校优班（可空，保存时按 年级+科目+班型 自动生成，班型留空则跳过）" maxlength="60" show-word-limit />
          </el-form-item>
        </el-col>

        <el-col :span="24">
          <el-form-item label="备注">
            <el-input v-model="form.remark" type="textarea" :rows="2" maxlength="200" show-word-limit />
          </el-form-item>
        </el-col>
      </el-row>
    </el-form>

    <div slot="footer" class="dialog-footer">
      <el-button @click="visible=false">取 消</el-button>
      <el-button type="primary" :loading="submitting || detailLoading" :disabled="detailError" @click="submit">确 定</el-button>
    </div>
  </el-dialog>
</template>

<script>
import { addTimetableSchedule, updateTimetableSchedule, getTimetableSchedule } from '@/api/system/timetable'

export default {
  name: 'TimetableDialog',
  props: {
    dims: { type: Object, required: true },
    preset: { type: Object, default: null }
  },
  data() {
    return {
      visible: false,
      submitting: false,
      detailLoading: false,
      detailError: false,
      form: this.buildEmptyForm(),
      rules: {
        gradeName:    [{ required: true, message: '请选择年级', trigger: 'change' }],
        subjectName:  [{ required: true, message: '请选择科目', trigger: 'change' }],
        teacherId:    [{ required: true, message: '请选择/填写教师', trigger: 'change' }],
        // 班型为可选项：留空不再拦截提交（仅作为课程班名称自动生成的拼装片段之一）
        classroomId:  [{ required: true, message: '请选择教室', trigger: 'change' }],
        timeSlot:     [{ required: true, message: '请选择时段', trigger: 'change' }]
      }
    }
  },
  computed: {
    /** 时段下拉 = 字典时段 ∪ 当前值（兼容导入进来的非标准时段，如「周六 09:00-11:00」） */
    timeSlotOptions() {
      const dict = (this.dims.timeSlots || []).map(t => t.value).filter(Boolean)
      const cur = this.form.timeSlot
      if (cur && dict.indexOf(cur) === -1) return dict.concat([cur])
      return dict
    }
  },
  methods: {
    // 教师下拉标签：真名优先；真名与昵称不同时把昵称作为别名附在后面
    teacherLabel(t) {
      const real = t.realName || t.userName || ''
      const nick = t.nickName || ''
      if (!real) return nick
      if (!nick || nick === real) return real
      return `${real}（${nick}）`
    },
    buildEmptyForm() {
      return {
        mode: 'add',
        scheduleId: null,
        courseYear: new Date().getFullYear(),
        termName: '', periodName: '',
        classroomId: null, classroomName: '', campusName: '',
        timeSlot: '',
        gradeName: '', subjectName: '', teacherName: '', teacherId: null, classType: '', classMode: this.dims.isTeacher && !this.dims.isAdmin ? '2' : '1', status: '0',
        startTime: '', endTime: '',
        startDate: null, endDate: null, classPattern: 'DAILY_5_1',
        recruitStatus: '0',
        enrolledCount: 0,
        courseClassName: '',
        remark: ''
      }
    },
    /** 由 classroomId 反查校区 / 教室名（后端只存 classroomId） */
    syncClassroomMeta() {
      const id = this.form.classroomId
      if (id === null || id === undefined || id === '') {
        this.form.classroomName = ''
        this.form.campusName = ''
        return
      }
      const c = (this.dims.classroomList || []).find(r => String(r.classroomId) === String(id))
      this.form.classroomName = c ? c.classroomName : ''
      this.form.campusName = c ? c.campusName : ''
    },
    /** 两种授课形式均使用真实教室；选教室不能偷偷改变授课形式。 */
    onClassroomChange(id) {
      this.syncClassroomMeta()
    },
    open() {
      this.form = this.buildEmptyForm()
      this.detailError = false
      if (this.preset && this.preset.defaults) {
        Object.assign(this.form, this.preset.defaults || {})
      }
      this.form.mode = (this.preset && this.preset.mode) || 'add'
      if (this.form.mode === 'add') {
        this.form.classPattern = ['周六', '周日'].includes(this.form.periodName) ? 'WEEKLY' : 'DAILY_5_1'
      }
      this.syncClassroomMeta()
      if (this.form.mode === 'edit' && this.form.scheduleId) {
        this.loadDetail(this.form.scheduleId)
      }
      this.visible = true
      this.$nextTick(() => {
        this.$refs.form && this.$refs.form.clearValidate()
      })
    },
    async loadDetail(id) {
      this.detailLoading = true
      try {
        const res = await getTimetableSchedule(id)
        Object.assign(this.form, res.data || {})
        this.syncClassroomMeta()
      } catch (e) { this.detailError = true } finally { this.detailLoading = false }
    },
    autoFillClassName() {
      if (this.form.courseClassName) return
      const parts = [this.form.gradeName, this.form.subjectName, this.form.classType]
        .filter(Boolean)
      this.form.courseClassName = parts.join('')
    },
    async submit() {
      if (this.submitting || this.detailLoading || this.detailError) return
      const valid = await new Promise(res => this.$refs.form.validate(ok => res(ok)))
      if (!valid) return
      this.autoFillClassName()
      this.submitting = true
      try {
        const payload = { ...this.form }
        // 后端只认 classroomId，去掉 classroomName/campusName 这两个前端辅助字段
        delete payload.classroomName
        delete payload.campusName
        delete payload.enrolledCount
        delete payload.delFlag
        delete payload.mode
        // 班课不写 lessonLocation，避免后端把 classroom_id 置空
        if (payload.classMode === '1') payload.lessonLocation = null
        if (this.form.scheduleId) {
          await updateTimetableSchedule(payload)
          this.$message.success('修改成功')
        } else {
          await addTimetableSchedule(payload)
          this.$message.success('新增成功')
        }
        this.visible = false
        this.$emit('success')
      } catch (e) {
        // request 拦截器会统一弹错
      } finally {
        this.submitting = false
      }
    }
  }
}
</script>

<style lang="scss" scoped>
.dim-readonly {
  display: flex; gap: 8px; flex-wrap: wrap; align-items: center;
  padding: 8px 14px;
  background: #f5f7fa;
  border-radius: 4px;
  margin-bottom: 10px;
  .dim-label { font-size: 13px; color: #606266; }
  .el-tag { font-weight: normal; }
  .dim-tip { font-size: 12px; color: #909399; margin-left: 4px; }
}
.move-hint {
  margin: 0 0 10px 0;
  padding: 6px 10px;
  background: #f0f9eb;
  border: 1px solid #e1f3d8;
  border-radius: 4px;
  color: #529b2e;
  font-size: 12px;
  line-height: 1.5;
}
</style>
