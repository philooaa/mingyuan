<template>
  <section class="leave-report attendance-panel">
    <div class="report-section-heading"><h3>{{teacherOnly ? "学生请假" : "请假课时核对"}}</h3><p>按学员和课程核对缺课，查看具体讲次与补课关联</p></div>
    <el-alert title="只统计已终审的请假；按讲次核对。已关联且终审到课的补课可抵扣，退款金额请结合收费记录核算。" type="info" :closable="false" show-icon/>
    <el-form label-position="top" size="small" class="report-filters" @submit.native.prevent="search">
      <el-form-item label="搜索学生或课程" class="report-keyword"><el-input v-model="query.keyword" maxlength="100" clearable placeholder="学生、课程、老师或联系电话" @keyup.enter.native="search"/></el-form-item>
      <el-form-item v-for="field in fields.filter(f=>!teacherOnly || f.key!=='teacherName')" :key="field.key" :label="field.label"><el-select v-model="query[field.key]" clearable filterable allow-create :placeholder="'全部'+field.label"><el-option v-for="value in options(field.key)" :key="value" :value="value" :label="value"/></el-select></el-form-item>
      <el-form-item label="请假日期" class="report-date"><el-date-picker v-model="dates" type="daterange" value-format="yyyy-MM-dd" start-placeholder="开始日期" end-placeholder="结束日期"/></el-form-item>
      <el-form-item label="处理状态" class="report-state"><el-select v-model="query.state"><el-option label="待处理 / 待核对" value="pending"/><el-option label="待补课" value="scheduled"/><el-option label="补课未完成" value="failed"/><el-option label="已补课" value="completed"/><el-option label="全部" value=""/></el-select></el-form-item>
      <el-form-item class="report-actions"><el-button type="primary" icon="el-icon-search" :loading="loading" @click="search">查询</el-button><el-button icon="el-icon-refresh" @click="reset">重置</el-button></el-form-item>
    </el-form>
    <div class="report-summary"><div><strong>{{report.studentCount || 0}}</strong><span>涉及学员</span></div><div><strong>{{report.leaveCount || 0}}</strong><span>请假课次</span></div><div><strong>{{report.makeupCount || 0}}</strong><span>已补课</span></div><div><strong>{{report.scheduledCount || 0}}</strong><span>待补课课次</span></div><div class="pending"><strong>{{report.pendingCount || 0}}</strong><span>待处理课次</span></div><div><strong>{{report.conflictCount || 0}}</strong><span>待核对课次</span></div></div>
    <el-alert v-if="error" :title="error" type="error" :closable="false"/>
    <div class="report-table-heading"><div><h3>学生请假清单</h3><p>展开左侧箭头，查看请假讲次和补课情况</p></div><span>共 {{report.total || 0}} 条学生课程记录</span></div>
    <el-table v-loading="loading" :data="report.rows || []" row-key="rowKey" border empty-text="没有符合条件的请假记录">
      <el-table-column type="expand"><template slot-scope="s"><el-table :data="s.row.lessons" size="small"><el-table-column label="请假讲次" width="110"><template slot-scope="d">{{d.row.lessonNo ? '第 '+d.row.lessonNo+' 讲' : '历史课次'}}</template></el-table-column><el-table-column label="请假日期" prop="classDate" width="130"/><el-table-column label="处理状态" width="130"><template slot-scope="d"><el-tag :type="d.row.state==='completed'?'success':'warning'">{{stateLabel(d.row.state)}}</el-tag></template></el-table-column><el-table-column label="关联补课"><template slot-scope="d">{{makeupLabel(d.row.makeup)}}</template></el-table-column><el-table-column label="操作" width="240"><template slot-scope="d"><el-button v-if="canManageMakeup() && d.row.canArrange" type="text" size="small" :disabled="writeBusy" @click="openMakeup(s.row,d.row)">{{d.row.makeup ? '重新安排' : '安排补课'}}</el-button><el-button v-if="d.row.signInId && d.row.lessonId" type="text" size="small" :disabled="writeBusy" @click="openMakeup(s.row,d.row,true)">安排记录</el-button><el-button v-if="canManageMakeup() && d.row.canCancel" type="text" size="small" :disabled="writeBusy" @click="openCancel(d.row)">取消安排</el-button><span v-if="canManageMakeup() && !d.row.canArrange && !d.row.canCancel && d.row.arrangeBlockedReason" class="report-note">{{d.row.arrangeBlockedReason}}</span></template></el-table-column></el-table></template></el-table-column>
      <el-table-column label="学生" prop="studentName" min-width="100"/><el-table-column label="联系电话" prop="contactPhone" width="130"/>
      <el-table-column label="课程" min-width="240"><template slot-scope="s"><b>{{s.row.courseName}}</b><div class="report-note">{{s.row.year}} {{s.row.termName}} · {{s.row.gradeName}} · {{s.row.subjectName}}</div></template></el-table-column>
      <el-table-column label="教师" prop="teacherName" width="110"/><el-table-column label="请假" prop="leaveCount" width="75" align="center"/><el-table-column label="已补课" prop="makeupCount" width="85" align="center"/><el-table-column label="待处理" width="90" align="center"><template slot-scope="s"><b class="pending-number">{{s.row.pendingCount}}</b></template></el-table-column><el-table-column label="待核对" prop="conflictCount" width="85" align="center"/>
    </el-table>
    <pagination v-show="report.total>0" :total="report.total || 0" :page.sync="pageNum" :limit.sync="pageSize" @pagination="load"/>
    <el-dialog :title="makeup.readOnly ? '补课安排记录' : '安排补课'" :visible.sync="makeup.visible" width="720px" custom-class="attendance-makeup-dialog" append-to-body :close-on-click-modal="false" :before-close="closeMakeup">
      <p class="report-note">{{makeup.source.studentName}} · 原请假：{{makeupLabel(makeup.source)}}</p>
      <el-alert v-if="makeup.error" :title="makeup.error" type="warning" :closable="false" show-icon/>
      <el-form v-if="!makeup.readOnly" label-position="top" size="small" v-loading="makeup.loading">
        <el-form-item label="补课方式"><el-radio-group v-model="makeup.mode" :disabled="writeBusy || makeup.uncertain" @change="modeChanged"><el-radio-button label="CLASS">插班补课</el-radio-button><el-radio-button label="CUSTOM">自定义补课</el-radio-button></el-radio-group></el-form-item>
        <template v-if="makeup.mode==='CLASS'">
        <el-form-item label="补课班级"><el-select v-model="makeup.scheduleId" filterable remote clearable :remote-method="loadMakeupOptions" :loading="makeup.loading" placeholder="搜索课程或教师" style="width:100%" :disabled="writeBusy || makeup.uncertain" @change="classChanged"><el-option v-for="c in makeup.courses" :key="c.scheduleId" :value="c.scheduleId" :label="courseLabel(c)"/></el-select></el-form-item>
        <el-form-item label="未来未提交课次"><el-select v-model="makeup.targetLessonId" clearable placeholder="先选择班级，再选择课次" style="width:100%" :disabled="!makeup.scheduleId || makeup.loading || writeBusy || makeup.uncertain"><el-option v-for="l in makeup.lessons" :key="l.lessonId" :value="l.lessonId" :label="l.label || lessonLabel(l)"/></el-select></el-form-item>
        </template>
        <template v-else>
          <el-form-item label="补课日期"><el-date-picker v-model="makeup.classDate" type="date" value-format="yyyy-MM-dd" placeholder="选择今天或未来日期" :disabled="writeBusy || makeup.uncertain"/></el-form-item>
          <div class="makeup-time-row"><el-form-item label="开始时间"><el-time-select v-model="makeup.startTime" :picker-options="{start:'00:00',step:'00:05',end:'23:55'}" placeholder="选择开始时间" :disabled="writeBusy || makeup.uncertain"/></el-form-item><el-form-item label="结束时间"><el-time-select v-model="makeup.endTime" :picker-options="{start:'00:00',step:'00:05',end:'23:55',minTime:makeup.startTime}" placeholder="选择结束时间" :disabled="writeBusy || makeup.uncertain"/></el-form-item></div>
          <el-form-item label="补课地点（必填）"><el-input v-model="makeup.location" maxlength="200" show-word-limit placeholder="填写教室或具体地点" :disabled="writeBusy || makeup.uncertain"/></el-form-item>
        </template>
        <el-form-item label="安排备注（选填）"><el-input v-model="makeup.remark" type="textarea" maxlength="500" show-word-limit :disabled="writeBusy || makeup.uncertain"/></el-form-item>
      </el-form>
      <p v-if="makeup.readOnly && !makeup.loading && !makeup.history.length" class="report-note">暂无补课安排记录</p><details v-if="makeup.history.length" :open="makeup.readOnly"><summary>安排历史（{{makeup.history.length}}）</summary><div v-for="h in makeup.history" :key="h.planId" class="makeup-history"><b>{{makeupLabel(h)}} · {{h.cancelled ? '已取消' : '已安排'}}</b><p>{{h.operatorName || '—'}} · {{h.createTime}}</p><p v-if="h.remark">备注：{{h.remark}}</p><p v-if="h.cancelReason">取消原因：{{h.cancelReason}}</p></div></details>
      <span slot="footer"><el-button @click="closeMakeup">关闭</el-button><el-button v-if="makeup.uncertain" :loading="makeup.loading" @click="refreshMakeup">刷新核对</el-button><el-button v-if="!makeup.readOnly" type="primary" :loading="writeBusy" :disabled="makeup.loading || makeup.uncertain || !canManageMakeup()" @click="submitMakeup">确认安排</el-button></span>
    </el-dialog>
    <el-dialog title="取消补课安排" :visible.sync="cancel.visible" width="480px" append-to-body :close-on-click-modal="false" :before-close="closeCancel">
      <el-alert v-if="cancel.error" :title="cancel.error" type="warning" :closable="false" show-icon/>
      <el-form label-position="top"><el-form-item label="取消原因（必填）"><el-input v-model="cancel.reason" type="textarea" maxlength="500" show-word-limit :disabled="writeBusy || cancel.uncertain"/></el-form-item></el-form>
      <span slot="footer"><el-button @click="closeCancel">关闭</el-button><el-button v-if="cancel.uncertain" :loading="loading" @click="refreshCancel">刷新核对</el-button><el-button type="primary" :loading="writeBusy" :disabled="cancel.uncertain || !cancel.reason.trim()" @click="submitCancel">确认取消</el-button></span>
    </el-dialog>
  </section>
</template>
<script>
import {attendanceCourses, attendanceLeaveReport, attendanceMakeupOptions, arrangeAttendanceMakeup, cancelAttendanceMakeup} from '@/api/system/signReview'
import request from '@/utils/request'
import {getToken} from '@/utils/auth'
const emptyQuery=()=>({keyword:'',year:'',termName:'',teacherName:'',gradeName:'',state:'pending'})
const emptyMakeup=()=>({visible:false,readOnly:false,source:{},signInId:null,scheduleId:null,targetLessonId:null,mode:'CLASS',classDate:'',startTime:'',endTime:'',location:'',remark:'',courses:[],lessons:[],history:[],loading:false,error:'',uncertain:false})
const emptyCancel=()=>({visible:false,signInId:null,planId:null,reason:'',error:'',uncertain:false})
export default {
  name:'AttendanceLeaveReport',props:{teacherOnly:{type:Boolean,default:false}},dicts:['edu_grade'],
  data(){return {makeup:emptyMakeup(),cancel:emptyCancel(),writeBusy:false,query:emptyQuery(),dates:null,pageNum:1,pageSize:20,report:{},courses:[],loading:false,error:'',fields:[{key:'year',label:'年份'},{key:'termName',label:'季度'},{key:'teacherName',label:'教师'},{key:'gradeName',label:'年级'}]}},
  created(){this._alive=true;const token=this._owner=getToken();this.search();(this.teacherOnly?request({url:'/miniapp/attendance/todos',params:{role:'teacher',allLessons:true}}):attendanceCourses()).then(r=>{if(this._alive&&token===getToken())this.courses=this.teacherOnly?((r.data||{}).courses||[]).map(c=>({...c,courseYear:c.year||c.courseYear})):r.data||[]}).catch(()=>{})},
  activated(){if(!this._alive||this._owner!==getToken()){this._alive=true;this._owner=getToken();this.search()}},
  deactivated(){this.closeMakeup();this.closeCancel();this._alive=false;this._version=(this._version||0)+1;this.report={}},
  beforeDestroy(){this.closeMakeup();this.closeCancel();this._alive=false;this._version=(this._version||0)+1},
  methods:{
    findLesson(signInId){return (this.report.rows||[]).flatMap(row=>row.lessons||[]).find(lesson=>String(lesson.signInId)===String(signInId))},
    canManageMakeup(){return this.teacherOnly===true && this.report.canManageMakeup===true && this.report.readOnly===false},
    params(){return {...this.query,...(this.teacherOnly?{scope:'teacher'}:{}),from:this.dates?.[0]||'',to:this.dates?.[1]||'',pageNum:this.pageNum,pageSize:this.pageSize}},
    options(key){const grades=(this.dict.type.edu_grade||[]).map(r=>r.label),rank=v=>grades.includes(v)?grades.indexOf(v):grades.length;return [...new Set(this.courses.map(c=>String(c[key==='year'?'courseYear':key]||'')).filter(Boolean))].sort((a,b)=>(key==='gradeName'?rank(a)-rank(b):0)||b.localeCompare(a,'zh-CN',{numeric:true}))},
    search(){this.pageNum=1;this._applied=this.params();return this.load()},reset(){this.query=emptyQuery();this.dates=null;return this.search()},
    async load(){if(!this._alive)return;const version=this._version=(this._version||0)+1,token=getToken();this.loading=true;this.error='';this.report={};try{const r=await attendanceLeaveReport({...this._applied,pageNum:this.pageNum,pageSize:this.pageSize});if(this._alive&&version===this._version&&token===getToken()){this.report=r.data||{};return true}}catch(e){if(this._alive&&version===this._version&&token===getToken())this.error=e.message||'请假课时表加载失败'}finally{if(this._alive&&version===this._version)this.loading=false}},
    stateLabel(state){return {pending:'待处理',scheduled:'待补课',failed:'补课未完成',completed:'已补课',conflict:'待核对'}[state]||''},
    currentDialog(version,token,kind='makeup'){return this._alive && token===getToken() && token===this._owner && version===this._dialogVersion && this[kind].visible},
    closeMakeup(){this._dialogVersion=(this._dialogVersion||0)+1;this._optionsVersion=(this._optionsVersion||0)+1;this.makeup=emptyMakeup()},
    closeCancel(){this._dialogVersion=(this._dialogVersion||0)+1;this.cancel=emptyCancel()},
    openMakeup(row,lesson,readOnly=false){if((!readOnly && !this.canManageMakeup()) || !this._alive || this._owner!==getToken() || this.writeBusy || (!readOnly && !lesson.canArrange) || !lesson.signInId)return;this.closeCancel();this.closeMakeup();this.makeup={...emptyMakeup(),visible:true,readOnly,source:{...row,...lesson},signInId:lesson.signInId,uncertain:!!this._uncertain && this._uncertainToken===getToken()};return this.loadMakeupOptions('')},
    async loadMakeupOptions(keyword=''){
      if(!this.makeup.visible || !this._alive || this._owner!==getToken())return false
      const version=this._optionsVersion=(this._optionsVersion||0)+1,dialog=this._dialogVersion,token=getToken(),scheduleId=this.makeup.scheduleId
      this.makeup.targetLessonId=null;this.makeup.lessons=[];this.makeup.loading=true;this.makeup.error=this.makeup.uncertain?'上次操作结果未确认，请刷新核对后再操作。':''
      try{const r=await attendanceMakeupOptions({signInId:this.makeup.signInId,...(scheduleId?{scheduleId}:{}),keyword:keyword||''});if(!this.currentDialog(dialog,token)||version!==this._optionsVersion)return false;const data=r.data||{};if(data.canManageMakeup===false)this.makeup.readOnly=true;this.makeup.source=data.source||this.makeup.source;this.makeup.courses=data.courses||[];this.makeup.lessons=this.makeup.mode==='CLASS' && scheduleId?(data.lessons||[]):[];this.makeup.history=data.history||[];return true}
      catch(e){if(this.currentDialog(dialog,token)&&version===this._optionsVersion)this.makeup.error=e.message||'补课选项加载失败';return false}
      finally{if(this.currentDialog(dialog,token)&&version===this._optionsVersion)this.makeup.loading=false}
    },
    modeChanged(){this._optionsVersion=(this._optionsVersion||0)+1;Object.assign(this.makeup,{scheduleId:null,targetLessonId:null,lessons:[],classDate:'',startTime:'',endTime:'',location:'',loading:false,error:''});if(this.makeup.mode==='CLASS')return this.loadMakeupOptions('')},
    classChanged(){return this.loadMakeupOptions('')},
    courseLabel(c){return [c.courseName,c.teacherName,c.year,c.termName,c.timeSlot].filter(Boolean).join(' · ')},
    lessonLabel(l){return [l.lessonNo?'第 '+l.lessonNo+' 讲':'',l.classDate,[l.startTime,l.endTime].filter(Boolean).join('–')].filter(Boolean).join(' · ')},
    async refreshMakeup(){const dialog=this._dialogVersion,token=getToken();const refreshed=await this.load();if(!this.currentDialog(dialog,token))return;if(refreshed && !this.makeup.readOnly && (!this.canManageMakeup() || !this.findLesson(this.makeup.signInId)?.canArrange)){this._uncertain=false;this.closeMakeup();this.$message?.warning?.('记录已更新，请查看当前补课安排');return}if(refreshed && await this.loadMakeupOptions('')){this._uncertain=false;this.makeup.uncertain=false;this.makeup.error=''}},
    openCancel(lesson){if(!this.canManageMakeup() || !this._alive || this._owner!==getToken() || this.writeBusy || !lesson.canCancel || !lesson.makeup?.planId)return;this.closeMakeup();this.closeCancel();this.cancel={...emptyCancel(),visible:true,signInId:lesson.signInId,planId:lesson.makeup.planId,uncertain:!!this._uncertain && this._uncertainToken===getToken(),error:this._uncertain && this._uncertainToken===getToken()?'上次操作结果未确认，请刷新核对后再操作。':''}},
    async refreshCancel(){const dialog=this._dialogVersion,token=getToken();if(await this.load() && this.currentDialog(dialog,token,'cancel')){this._uncertain=false;this.closeCancel()}},
    submitMakeup(){
      if(this.makeup.readOnly || !this.canManageMakeup() || !this.findLesson(this.makeup.signInId)?.canArrange || !this.makeup.signInId || this.makeup.loading)return
      const m=this.makeup,body={signInId:m.signInId,mode:m.mode},fail=message=>{this.makeup.error=message}
      if(m.remark.length>500)return fail('安排备注不能超过500字')
      if(m.mode==='CLASS'){
        if(!m.targetLessonId || !m.lessons.some(l=>l.lessonId===m.targetLessonId))return fail('请选择未来未提交课次')
        body.targetLessonId=m.targetLessonId
      }else if(m.mode==='CUSTOM'){
        const date=new Date(m.classDate+'T00:00:00')
        if(!/^\d{4}-\d{2}-\d{2}$/.test(m.classDate) || isNaN(date.getTime()) || date.getFullYear()!==Number(m.classDate.slice(0,4)) || date.getMonth()+1!==Number(m.classDate.slice(5,7)) || date.getDate()!==Number(m.classDate.slice(8,10)))return fail('请选择有效的补课日期')
        const time=/^([01]\d|2[0-3]):[0-5]\d$/
        if(!time.test(m.startTime) || !time.test(m.endTime) || m.endTime<=m.startTime)return fail('结束时间必须晚于开始时间')
        if(new Date(m.classDate+'T'+m.startTime+':00+08:00')<=new Date())return fail('补课开始时间必须晚于当前时间')
        if(!m.location.trim() || m.location.length>200)return fail('请填写200字以内的补课地点')
        Object.assign(body,{classDate:m.classDate,startTime:m.startTime,endTime:m.endTime,location:m.location.trim()})
      }else return
      body.remark=m.remark.trim();return this.writeMakeup('makeup',body)
    },
    submitCancel(){const reason=this.cancel.reason.trim();if(!this.cancel.planId || !reason || reason.length>500)return;return this.writeMakeup('cancel',{planId:this.cancel.planId,reason})},
    async writeMakeup(kind,body){
      if(!this.canManageMakeup() || this.writeBusy || (this._uncertain && this._uncertainToken===getToken()) || !this[kind].visible || !this._alive || this._owner!==getToken())return
      const latest=this.findLesson(kind==='makeup'?body.signInId:this.cancel.signInId)
      if(kind==='makeup'?!latest?.canArrange:!latest?.canCancel || String(latest.makeup?.planId)!==String(body.planId))return
      const dialog=this._dialogVersion,token=getToken();this.writeBusy=true;this[kind].error=''
      try{const r=await (kind==='makeup'?arrangeAttendanceMakeup(body):cancelAttendanceMakeup(body));if(r && r.code!==undefined && r.code!==200)throw new Error(r.msg||'操作未完成');if(token!==getToken() || token!==this._owner)return;this._uncertain=false;if(this.currentDialog(dialog,token,kind)){this[kind==='makeup'?'closeMakeup':'closeCancel']();this.$message.success(kind==='makeup'?'补课已安排':'补课安排已取消')}await this.load()}
      catch(e){if(token===getToken() && token===this._owner){this._uncertain=true;this._uncertainToken=token}if(this.currentDialog(dialog,token,kind)){this[kind].uncertain=true;this[kind].error=(e.message||'操作失败')+'。操作结果未确认，请刷新核对，避免重复提交。'}}
      finally{this.writeBusy=false}
    },
    makeupLabel(m){return m?[m.arrangeType==='CUSTOM'?'自定义补课':m.courseName,m.lessonNo?'第 '+m.lessonNo+' 讲':'',m.classDate,[m.startTime,m.endTime].filter(Boolean).join('–'),m.location].filter(Boolean).join(' · '):'—'}

  }
}
</script>
<style>
.attendance-makeup-dialog {max-width:calc(100% - 32px);}
.attendance-makeup-dialog .makeup-time-row {display:flex;gap:16px;flex-wrap:wrap;}
.attendance-makeup-dialog .makeup-time-row .el-form-item {flex:1 1 200px;min-width:0;}
.attendance-makeup-dialog .el-date-editor {max-width:100%;}
</style>
<style scoped>
.makeup-history {padding:12px 0;border-bottom:1px solid #e6ecf4;}
.makeup-history p {margin:6px 0;color:#61758e;}
.report-section-heading h3,.report-table-heading h3 {margin:0;color:#334e70;font-size:16px;font-weight:600;}
.report-section-heading p,.report-table-heading p {margin:7px 0 16px;font-size:12px;line-height:1.7;color:#92a0b4;}
.report-filters {display:flex;align-items:flex-end;flex-wrap:wrap;gap:16px;margin-top:20px;padding:20px;background:#f7f9fc;border:1px solid #eaf0f6;border-radius:12px;}
.report-filters ::v-deep .el-form-item {flex:1 1 125px;min-width:0;margin:0;}
.report-filters ::v-deep .el-form-item__content,.report-filters ::v-deep .el-select,.report-filters ::v-deep .el-date-editor {width:100%;min-width:0;}
.report-filters ::v-deep .el-form-item__label {padding-bottom:9px;line-height:18px;color:#61758e;font-size:13px;}
.report-filters ::v-deep .report-keyword {flex:2 1 230px;}
.report-filters ::v-deep .report-date {flex:2 1 250px;}
.report-filters ::v-deep .report-state {flex:0 1 180px;}
.report-filters ::v-deep .report-actions {flex:0 0 auto;margin-left:auto;}
.report-summary {display:grid;grid-template-columns:repeat(6,minmax(0,1fr));gap:16px;margin:22px 0;}
.report-summary>div {padding:20px 22px;border:1px solid #e6ecf4;border-radius:12px;background:#fff;display:flex;flex-direction:column;gap:10px;}
.report-summary strong {font-size:30px;line-height:1.2;color:#2c4b76;font-variant-numeric:tabular-nums;}
.report-summary span,.report-note {font-size:12px;color:#8797ad;}
.report-summary .pending {background:#fffbf3;border-color:#f1e9d9;}
.report-summary .pending strong,.pending-number {color:#b18235;}
.report-note {margin-top:7px;line-height:1.7;}
.report-table-heading {display:flex;justify-content:space-between;align-items:center;gap:20px;margin:26px 0 16px;}
.report-table-heading p {margin-bottom:0;}
.report-table-heading>span {color:#92a0b4;font-size:12px;}
.leave-report ::v-deep .el-table__expanded-cell {padding:20px 32px;background:#fafbfd;}
.leave-report ::v-deep .el-pagination {padding-top:20px;}
@media(max-width:1100px) {.report-summary {grid-template-columns:repeat(3,minmax(0,1fr));}}
@media(max-width:700px) {.report-summary {grid-template-columns:repeat(2,minmax(0,1fr));}.report-summary>div {padding:16px;}.report-table-heading {flex-wrap:wrap;}.report-filters ::v-deep .report-actions {flex-basis:100%;}}
</style>
