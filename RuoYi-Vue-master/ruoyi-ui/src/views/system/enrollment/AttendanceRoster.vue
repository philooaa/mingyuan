<template>
  <div style="margin-bottom:20px">
    <el-button v-hasRole="['education','admin']" @click="open">按讲次维护学员</el-button>
    <el-dialog title="课程学员生效范围" :visible.sync="visible" width="820px" :close-on-click-modal="!busy">
      <el-alert v-if="error" :title="error" type="error" :closable="false"/>
      <p>加入名单不会自动收款；已锁定课次保留历史快照。新增学生请先建立档案。</p>
      <el-select v-model="form.scheduleId" filterable placeholder="选择课程季度" :disabled="busy" @change="changeCourse"><el-option v-for="c in courses" :key="c.scheduleId" :value="String(c.scheduleId)" :label="[c.courseName,c.year,c.termName].filter(Boolean).join(' · ')"/></el-select>
      <el-select v-model="lessonId" placeholder="选择查看讲次" :disabled="busy" @change="loadRows"><el-option v-for="l in lessons" :key="l.lessonId" :value="l.lessonId" :label="`第${l.lessonNo}讲 · ${l.classDate}${l.locked?'（锁定）':''}`"/></el-select>
      <el-table :data="rows"><el-table-column label="学生" prop="studentName"/><el-table-column label="档案编号" prop="studentId"/><el-table-column label="开始讲次" prop="startLesson"/><el-table-column label="结束讲次" prop="endLesson"/><el-table-column label="操作"><template slot-scope="s"><el-button type="text" :disabled="busy" @click="remove(s.row)">按讲次移出</el-button></template></el-table-column></el-table>
      <el-form label-width="110px" style="margin-top:20px">
        <el-form-item label="关联学生档案"><el-select v-model="form.studentId" filterable remote :remote-method="search" placeholder="输入姓名或手机搜索"><el-option v-for="s in students" :key="s.studentId" :value="String(s.studentId)" :label="`${s.studentName} · 档案${s.studentId} · ${s.contactPhone||''}`"/></el-select></el-form-item>
        <el-form-item label="关联报名编号"><el-input v-model="form.enrollmentId" placeholder="正常报名必填"/></el-form-item>
        <el-form-item label="开始讲次"><el-select v-model="form.startLesson"><el-option v-for="l in lessons" :key="l.lessonId" :value="Number(l.lessonNo)" :label="`第${l.lessonNo}讲 · ${l.classDate}`"/></el-select></el-form-item>
        <el-form-item label="结束讲次"><el-select v-model="form.endLesson" clearable placeholder="可空，到季度结束"><el-option v-for="l in lessons" :key="l.lessonId" :value="Number(l.lessonNo)" :label="`第${l.lessonNo}讲 · ${l.classDate}`"/></el-select></el-form-item>
        <el-form-item label="名单来源"><el-select v-model="form.source"><el-option label="正常报名" value="ENROLLED"/><el-option label="线下/历史插班" value="OFFLINE"/><el-option label="试听" value="TRIAL"/><el-option label="临时调课" value="TRANSFER"/></el-select></el-form-item>
        <el-form-item label="收费状态"><el-select v-model="form.paymentStatus"><el-option label="未收费" value="UNPAID"/><el-option label="历史收费已核对" value="HISTORICAL"/><el-option label="沿用报名记录" value="ENROLLMENT"/></el-select></el-form-item>
        <el-form-item label="调整原因"><el-input v-model="form.reason" maxlength="500"/></el-form-item>
      </el-form>
      <div slot="footer"><el-button :disabled="busy" @click="visible=false">关闭</el-button><el-button type="primary" :loading="busy" :disabled="!lessons.length" @click="save">保存生效范围</el-button></div>
    </el-dialog>
  </div>
</template>
<script>
import {rosterCourses,rosterLessons,rosterStudents,rosterList,rosterAdd,rosterRemove} from '@/api/system/attendanceRoster'
import {getToken} from '@/utils/auth'
export default {
  name:'AttendanceRoster',data(){return {visible:false,busy:false,error:'',courses:[],lessons:[],students:[],rows:[],lessonId:null,form:{scheduleId:'',studentId:'',enrollmentId:'',startLesson:1,endLesson:'',source:'OFFLINE',paymentStatus:'UNPAID',reason:''}}},
  methods:{
    current(){return this.visible&&this._token===getToken()},
    async run(fn){if(this.busy)return;this.busy=true;this.error='';try{await fn()}catch(e){if(this.current())this.error=e.message||'操作失败'}finally{this.busy=false}},
    open(){this.visible=true;this._token=getToken();this.rows=[];this.lessons=[];this.form.scheduleId='';this.run(async()=>{const r=await rosterCourses();if(this.current())this.courses=r.data||[]})},
    changeCourse(){this.run(async()=>{this.rows=[];this.lessons=[];this.lessonId=null;const r=await rosterLessons(this.form.scheduleId);if(!this.current())return;this.lessons=r.data||[];this.lessonId=this.lessons[0]?.lessonId;await this.fetchRows()})},
    async fetchRows(){if(!this.lessonId)return;const r=await rosterList(this.form.scheduleId,this.lessonId);if(this.current())this.rows=r.data||[]},loadRows(){this.run(()=>this.fetchRows())},
    async search(keyword){const sequence=this._search=(this._search||0)+1;try{const r=await rosterStudents(keyword);if(this.current()&&sequence===this._search)this.students=r.data||[]}catch(e){if(this.current())this.error=e.message}},
    save(){this.run(async()=>{const f=this.form;if(!f.studentId||!f.scheduleId||!f.reason.trim())throw new Error('请选择课程和学生并填写原因');if(f.source==='ENROLLED'&&!f.enrollmentId)throw new Error('正常报名须关联报名编号');if(f.endLesson&&Number(f.endLesson)<Number(f.startLesson))throw new Error('结束讲次不能早于开始讲次');await rosterAdd({...f,endLesson:f.endLesson||null});if(this.current()){await this.fetchRows();this.$modal.msgSuccess('已保存')}})},
    remove(row){this.run(async()=>{const r=await this.$prompt('从第几讲起移出？历史点名保持不变。','生效讲次',{inputValidator:v=>/^[1-9]\d*$/.test(v),inputErrorMessage:'请输入有效讲次'});if(!this.current())return;if(!this.form.reason.trim())throw new Error('请先填写调整原因');await rosterRemove(row,{scheduleId:this.form.scheduleId,effectiveLesson:Number(r.value),reason:this.form.reason});if(this.current())await this.fetchRows()})}
  }
}
</script>
