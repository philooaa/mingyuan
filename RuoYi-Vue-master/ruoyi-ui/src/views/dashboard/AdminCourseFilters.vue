<template>
  <div class="course-filters">
    <el-form :inline="true" size="small" @submit.native.prevent>
      <el-form-item label="年份"><el-select v-model="selection.year" clearable placeholder="全部年份" @change="changed('year')"><el-option v-for="x in years" :key="x" :label="x" :value="x" /></el-select></el-form-item>
      <el-form-item label="学期"><el-select v-model="selection.term" clearable placeholder="全部学期" @change="changed('term')"><el-option v-for="x in terms" :key="x" :label="x" :value="x" /></el-select></el-form-item>
      <el-form-item label="学段"><el-select v-model="selection.stage" @change="changed('stage')"><el-option v-for="x in stages" :key="x.value" :label="x.label" :value="x.value" /></el-select></el-form-item>
      <el-form-item label="年级"><el-select v-model="selection.grade" clearable placeholder="全部年级" @change="changed('grade')"><el-option v-for="x in grades" :key="x" :label="x" :value="x" /></el-select></el-form-item>
      <el-form-item label="科目"><el-select v-model="selection.subject" clearable placeholder="全部科目" @change="changed('subject')"><el-option v-for="x in subjects" :key="x" :label="x" :value="x" /></el-select></el-form-item>
      <el-form-item label="教师"><el-select v-model="selection.teacherId" clearable filterable placeholder="全部教师" @change="changed('teacher')"><el-option v-for="x in teachers" :key="x.id" :label="x.name" :value="x.id" /></el-select></el-form-item>
      <el-form-item label="指标"><el-select v-model="selection.metric" @change="changed('metric')"><el-option v-for="x in metrics" :key="x.value" :label="x.label" :value="x.value" /></el-select></el-form-item>
      <el-form-item><el-button @click="reset">重置</el-button></el-form-item>
    </el-form>
  </div>
</template>

<script>
const defaultSelection = () => ({ year: '', term: '', stage: '', grade: '', subject: '', teacherId: '', metric: 'income' })
const stageOf = value => {
  const name = String(value || '').trim()
  if (/^(小学)?[一二三四五六]年级$|^小[一二三四五六]$|^[1-6]年级$/.test(name)) return 'primary'
  if (/^初[一二三]$|^初中[一二三]年级$|^[七八九]年级$|^[7-9]年级$/.test(name)) return 'junior'
  if (/^高[一二三]$|^高中[一二三]年级$/.test(name)) return 'senior'
  return ''
}
export default {
  name: 'AdminCourseFilters',
  props: { dimensions: { type: Array, default: () => [] } },
  data() { return { selection: defaultSelection(), stages: [{label:'总人次',value:''},{label:'高中人次',value:'senior'},{label:'初中人次',value:'junior'},{label:'小学人次',value:'primary'}], metrics: [{label:'收入',value:'income'},{label:'退费',value:'refund'},{label:'毛利',value:'grossProfit'}] } },
  computed: {
    years() { return this.unique(this.dimensions, 'courseYear').sort().reverse() },
    yearRows() { return this.dimensions.filter(x => !this.selection.year || String(x.courseYear) === this.selection.year) },
    terms() { return this.unique(this.yearRows, 'termName') },
    termRows() { return this.yearRows.filter(x => !this.selection.term || x.termName === this.selection.term) },
    stageRows() { return this.termRows.filter(x => !this.selection.stage || stageOf(x.gradeName) === this.selection.stage) },
    grades() { return this.unique(this.stageRows, 'gradeName') },
    gradeRows() { return this.stageRows.filter(x => !this.selection.grade || x.gradeName === this.selection.grade) },
    subjects() { return this.unique(this.gradeRows, 'subjectName') },
    subjectRows() { return this.gradeRows.filter(x => !this.selection.subject || x.subjectName === this.selection.subject) },
    teachers() { const out = new Map(); this.subjectRows.forEach(x => { if (x.teacherId != null) out.set(String(x.teacherId), { id: String(x.teacherId), name: x.teacherName || '教师待核对' }) }); return [...out.values()].sort((a,b) => a.name.localeCompare(b.name, 'zh-CN')) }
  },
  methods: {
    unique(rows, key) { return [...new Set(rows.map(x => x[key] == null ? '' : String(x[key]).trim()).filter(Boolean))] },
    changed(level) {
      const order=['year','term','stage','grade','subject','teacherId']; const start=order.indexOf(level === 'teacher' ? 'teacherId' : level)
      if (start >= 0) order.slice(start+1).forEach(key => { this.selection[key] = '' })
      this.$emit('change', { ...this.selection })
    },
    reset() { this.selection=defaultSelection(); this.$emit('change', { ...this.selection }) }
  }
}
</script>
<style scoped>.course-filters { padding: 16px 16px 0; background: #fff; border-radius: 6px; } .el-select { width: 150px; }</style>
