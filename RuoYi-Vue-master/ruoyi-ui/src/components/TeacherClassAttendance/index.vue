<template>
  <div v-loading="loading">
    <el-divider content-position="left">班课人次提成（人次）</el-divider>
    <el-form-item label="统计月份"><el-date-picker v-model="month" type="month" value-format="yyyy-MM" :clearable="false" :disabled="saving" @change="load" /></el-form-item>
    <el-alert v-if="error" :title="error" type="error" :closable="false" />
    <template v-if="info">
      <el-form-item label="班课人次"><el-input-number v-model="count" :min="0" :max="1000000000" :precision="0" :disabled="saving || !canAdjust" /><span style="margin-left:8px">自动统计 {{ info.automaticCount }} 人次 · {{ info.manual ? '已手动调整' : '自动生成' }}</span></el-form-item>
      <p>每位学生每上一节班课计1人次，含特价课、试听及调课到课；一对一不计。仅统计终审通过的到课记录，不参与金额计算。</p>
      <el-button v-if="canAdjust" :loading="saving" :disabled="saving" size="small" @click="save(false)">保存人次</el-button>
      <el-button v-if="canAdjust" :disabled="saving || !info.manual" size="small" @click="save(true)">恢复自动统计</el-button>
    </template>
  </div>
</template>
<script>
import request from '@/utils/request'
export default {
  name: 'TeacherClassAttendance',
  props: { teacherId: { required: true } },
  data () { const now = new Date(); return { month: `${now.getFullYear()}-${String(now.getMonth()+1).padStart(2,'0')}`, loading: false, saving: false, error: '', info: null, count: 0, serial: 0 } },
  computed: { canAdjust () { const roles = this.$store.getters.roles || []; const perms = this.$store.getters.permissions || []; return roles.some(r => ['admin','business_admin'].includes(r)) && (perms.includes('*:*:*') || perms.includes('system:salaryConfig:edit')) } },
  watch: { teacherId: { immediate: true, handler () { this.load() } } },
  beforeDestroy () { this.serial++ },
  methods: {
    async load () {
      if (!this.teacherId || !this.month) return
      const serial = ++this.serial; this.loading = true; this.info = null; this.error = ''
      try { const r = await request({ url: `/system/salaryConfig/class-attendance/${this.teacherId}`, params: { salaryMonth: this.month } }); if(serial === this.serial){this.info = r.data; this.count = r.data.count} }
      catch(e) { if(serial === this.serial)this.error = e.message || '人次加载失败' }
      finally { if(serial === this.serial)this.loading = false }
    },
    async save (reset) {
      if(this.saving || !this.info || !this.canAdjust)return
      this.saving = true
      const serial = this.serial
      try { const r = await request({ url: `/system/salaryConfig/class-attendance/${this.teacherId}`, method: 'put', params: { salaryMonth: this.month }, data: { count: reset ? null : this.count } }); if(serial === this.serial){this.info=r.data;this.count=r.data.count;this.$message.success(reset?'已恢复自动统计':'人次已保存')} }
      finally { this.saving = false }
    }
  }
}
</script>
