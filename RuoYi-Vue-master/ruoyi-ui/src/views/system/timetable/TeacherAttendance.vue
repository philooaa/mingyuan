<template>
  <div>
    <el-button v-hasRole="['teacher']" size="small" icon="el-icon-tickets" @click="open">课程出勤</el-button>
    <el-dialog title="课程出勤" :visible.sync="visible" append-to-body width="860px">
      <el-alert v-if="error" :title="error" type="error" :closable="false"/>
      <el-form v-if="!courseId" inline size="small">
        <el-form-item v-for="field in fields" :key="field.key" :label="field.label">
          <el-select v-model="selected[field.key]" clearable :placeholder="'全部' + field.label" style="width: 120px">
            <el-option v-for="value in options(field.key)" :key="value" :value="value"
               :label="value"/>
          </el-select>
        </el-form-item>
        <el-form-item><el-button type="primary" :disabled="loading" @click="query">查询</el-button></el-form-item>
        <el-form-item><el-button @click="reset">重置</el-button></el-form-item>
      </el-form>
      <el-table v-if="!courseId && queried" v-loading="loading" :data="filteredCourses" empty-text="没有符合条件的班级">
        <el-table-column label="课程" prop="courseName"/>
        <el-table-column label="年份" prop="year"/>
        <el-table-column label="季度" prop="termName"/>
        <el-table-column label="年级" prop="gradeName"/>
        <el-table-column label="操作"><template slot-scope="{ row }">
          <el-button type="text" @click="courseId=String(row.scheduleId)">查看讲次</el-button>
        </template></el-table-column>
      </el-table>
      <el-button v-if="courseId" size="small" @click="courseId=''">返回查询结果</el-button>
      <el-table v-if="courseId" v-loading="loading" :data="filtered" max-height="460" empty-text="暂无符合条件的课次">
        <el-table-column label="课程" prop="courseName" min-width="150"/>
        <el-table-column label="季度" prop="termName" width="90"/>
        <el-table-column label="年级" prop="gradeName" width="90"/>
        <el-table-column label="讲次" prop="lessonNo" width="70"/>
        <el-table-column label="上课日期" prop="classDate" width="115"/>
        <el-table-column label="上课时间" width="125">
          <template slot-scope="{ row }">{{ [row.startTime,row.endTime].filter(Boolean).join('–') }}</template>
        </el-table-column>
        <el-table-column label="状态" width="86">
          <template slot-scope="{ row }">{{ ({FINAL:'已终审',PENDING_FIRST:'待初审',PENDING_FINAL:'待终审'})[row.stage] || (row.upcoming ? '未开课' : row.overdue ? '已逾期' : '待点名') }}</template>
        </el-table-column>
      </el-table>
    </el-dialog>
  </div>
</template>

<script>
import request from '@/utils/request'
import { getToken } from '@/utils/auth'

const fields = [
  { key: 'year', label: '年份' }, { key: 'termName', label: '季度' },
  { key: 'gradeName', label: '年级' }
]

export default {
  dicts: ['edu_grade'],
  data() {
    return { fields, visible: false, loading: false, error: '', rows: [], courses: [], courseId: '', selected: {}, applied: {}, queried: false }
  },
  computed: {
    filteredCourses() {
      return this.courses.filter(course => ['year','termName','gradeName'].every(key => !this.applied[key] || String(course[key]) === this.applied[key]))
    },
    filtered() {
      if (!this.courseId) return []
      return this.rows.filter(row => String(row.scheduleId) === this.courseId)
    }
  },
  methods: {
    query() { this.applied = { ...this.selected }; this.queried = true },
    reset() { this.courseId = ''; this.queried = false; this.selected = { year: '', termName: '', gradeName: '' } },
    options(key) {
      const rows = this.courses
      const values = [...new Set(rows.map(row => row[key]).filter(value => value != null && value !== '').map(String))]
      const gradeOrder = (this.dict.type.edu_grade || []).map(grade => grade.label)
      const rank = value => gradeOrder.includes(value) ? gradeOrder.indexOf(value) : gradeOrder.length
      return values.sort((a,b) => key === 'gradeName' && rank(a) !== rank(b)
        ? rank(a) - rank(b) : a.localeCompare(b, 'zh-CN', {numeric:true}))
    },
    async open() {
      const token = getToken()
      const version = this._version = (this._version || 0) + 1
      this.visible = true
      this.loading = true
      this.error = ''
      this.rows = []
      this.courses = []
      this.reset()
      try {
        const result = await request({url:'/miniapp/attendance/todos',params:{role:'teacher',allLessons:true}})
        if (version === this._version && token === getToken() && this.visible) {
          this.rows = result.data?.items || []
          this.courses = result.data?.courses || []
        }
      } catch (error) {
        if (version === this._version && token === getToken() && this.visible) this.error = error.message || '课次加载失败'
      } finally {
        if (version === this._version) this.loading = false
      }
    }
  }
}
</script>
