<template>
    <el-dialog title="修改计费课次数" :visible.sync="countOpen" width="500px" append-to-body :close-on-click-modal="false" :show-close="!countSaving" :close-on-press-escape="!countSaving">
      <p>{{ countCourse.courseClassName }}</p>
      <el-alert title="仅更新课程后续定价；已有报名订单、核价、缴费及收据金额均不改变，也不会增减实际排课。" type="info" :closable="false" />
      <p>当前计费：{{ countCourse.lessonCount == null ? '待配置' : countCourse.lessonCount + ' 次' }}（{{ countCourse.manualLessonCount ? '手动设置' : countCourse.systemLessonCount == null ? '历史值' : '系统自动计算' }}）</p>
      <p>系统计算：{{ countCourse.systemLessonCount == null ? '日期或规则未配置完整' : countCourse.systemLessonCount + ' 次' }}</p>
      <el-radio-group v-model="countMode" :disabled="countSaving">
        <el-radio label="AUTO" :disabled="countCourse.systemLessonCount == null">系统自动计算</el-radio>
        <el-radio label="MANUAL">手动设置</el-radio>
      </el-radio-group>
      <el-form label-position="top" style="margin-top:16px" v-if="countMode === 'MANUAL'">
        <el-form-item label="计费课次数"><el-input-number v-model="countValue" :min="1" :max="3661" :precision="0" :disabled="countSaving" /></el-form-item>
        <el-form-item label="修改原因（与系统计算不一致时必填）"><el-input v-model="countReason" type="textarea" maxlength="255" show-word-limit :disabled="countSaving" /></el-form-item>
      </el-form>
      <span slot="footer"><el-button :disabled="countSaving" @click="countOpen=false">取消</el-button><el-button type="primary" :loading="countSaving" @click="saveLessonCount">保存</el-button></span>
    </el-dialog>
</template>
<script>
import { getCourseLessonCount, updateCourseLessonCount } from '@/api/system/coursePrice'
export default {
  name:'CourseLessonCountEditor',
  data() { return {countOpen:false,countLoading:false,countSaving:false,countCourse:{},countMode:'AUTO',countValue:1,countReason:''} },
  computed: {
    canEditLessonCount() { return (this.$store.getters.roles || []).some(role => ['admin','business_admin','education'].includes(role)) },
  },
  methods: {
    async openLessonCount(row) {
      if (!this.canEditLessonCount) { this.$modal.msgError('仅教务和管理员可修改计费课次数'); return }
      if (this.countLoading || this.countSaving) return
      this.countLoading=true
      try {
        const r=await getCourseLessonCount(row.scheduleId)
        this.countCourse=r.data
        this.countMode=r.data.manualLessonCount || r.data.systemLessonCount == null ? 'MANUAL' : 'AUTO'
        this.countValue=r.data.lessonCount || 1
        this.countReason=r.data.lessonCountOverrideReason || ''
        this.countOpen=true
      } catch(e) { this.$modal.msgError(e.message || '课次数加载失败') }
      finally { this.countLoading=false }
    },
    async saveLessonCount() {
      if (!this.canEditLessonCount || !this.countOpen || this.countSaving) return
      const manual=this.countMode === 'MANUAL', count=manual?Number(this.countValue):this.countCourse.systemLessonCount
      if (!Number.isInteger(count) || count<1 || count>3661) { this.$modal.msgError('请填写1至3661的整数课次数'); return }
      const mismatch=manual && count!==this.countCourse.systemLessonCount
      if (mismatch && !this.countReason.trim()) { this.$modal.msgError('与系统计算不一致，请填写修改原因'); return }
      this.countSaving=true
      try {
        if (mismatch) {
          try { await this.$confirm(`系统计算为${this.countCourse.systemLessonCount == null ? '待配置' : this.countCourse.systemLessonCount+'次'}，你填写了${count}次。确认按${count}次用于后续计费？已有订单金额不会改变。`,'课次数不一致提醒',{type:'warning',confirmButtonText:'确认修改',cancelButtonText:'返回修改'}) }
          catch(e) { return }
        }
        await updateCourseLessonCount(this.countCourse.scheduleId,{mode:this.countMode,lessonCount:manual?count:null,reason:this.countReason.trim(),confirmMismatch:mismatch,version:this.countCourse.lessonCountVersion})
        this.countOpen=false;this.$modal.msgSuccess('计费课次数已更新，已有订单金额不变')
        this.$emit('saved')
      } catch(e) { this.$modal.msgError(e.message || '保存失败，请重新打开核对') }
      finally { this.countSaving=false }
    },

  },
}
</script>
