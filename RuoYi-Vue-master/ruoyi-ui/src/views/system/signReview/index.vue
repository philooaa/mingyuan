<template>
  <div class="app-container">
    <h2>学生出勤</h2>
    <el-tabs v-model="mode" @tab-click="changeMode"><el-tab-pane label="出勤总览" name="overview"/><el-tab-pane label="出勤复核" name="review"/></el-tabs>
    <div v-if="mode==='overview'">
      <el-form inline size="small"><el-form-item><el-input v-model="courseKeyword" maxlength="100" placeholder="搜索课程、老师、年级或学科" @keyup.enter.native="loadCourses"/></el-form-item><el-form-item><el-button @click="loadCourses">查询课程</el-button></el-form-item><el-form-item><el-select v-model="courseId" filterable placeholder="选择课程" style="width:420px" @change="loadOverview"><el-option v-for="c in courses" :key="c.scheduleId" :value="c.scheduleId" :label="courseLabel(c)"/></el-select></el-form-item><el-form-item><el-date-picker v-model="dateRange" type="daterange" value-format="yyyy-MM-dd" start-placeholder="开始日期" end-placeholder="结束日期" @change="loadOverview"/></el-form-item></el-form>
      <el-alert v-if="overviewError" :title="overviewError" type="error" :closable="false"/>
      <div v-loading="overviewLoading">
        <template v-if="overview">
          <div class="overview-summary"><span><b>{{overview.occurrenceCount}}</b> 范围内课次</span><span><b>{{overview.stats.attended}}</b> 正式实到人次</span><span><b>{{overview.stats.leave}}</b> 请假人次</span><span>{{overview.rows.length}}位学员</span></div>
          <p class="overview-note">展示计划课次及历史记录；— 表示未记录。正式汇总只含已终审；待核实汇总不计入正式人数及课酬。</p>
          <p>季度／当前日期范围计划应到 {{overview.plannedExpectedCount || 0}} 人次</p>
          <p>正式应到 {{overview.stats.expectedCount || 0}} 人次 · 正式实到 {{overview.stats.attended || 0}} 人次</p>
          <p v-if="overview.pendingStats">待核实应到 {{overview.pendingStats.expectedCount || 0}} 人次 · 实到 {{overview.pendingStats.attended || 0}} 人次；正常 {{overview.pendingStats.normal || 0}} · 试听 {{overview.pendingStats.trial || 0}} · 调课 {{overview.pendingStats.transfer || 0}} · 请假 {{overview.pendingStats.leave || 0}} · 录播 {{overview.pendingStats.video || 0}} · 缺席 {{overview.pendingStats.absent || 0}} · 尚未点名 {{overview.pendingStats.unmarked || 0}}</p>
          <el-alert v-if="overview.stats.conflicts" :title="overview.stats.conflicts+'个学员课次有多条记录，需核对，未计入确定出勤结果'" type="warning" :closable="false"/>
          <el-input v-model="studentKeyword" size="small" placeholder="查找本课学生姓名或家长手机号" clearable style="width:320px;margin:12px 0"/>
          <el-table :data="visibleStudents" row-key="studentKey" height="480" border><el-table-column label="学生 / 统计" fixed="left" width="200"><template slot-scope="r"><el-button type="text" @click="openStudent(r.row)">{{r.row.studentName}}</el-button><div class="overview-note">{{r.row.temporary?'临时学员记录':r.row.parentPhone?'家长尾号 '+String(r.row.parentPhone).slice(-4):'未留家长电话'}}</div><div class="overview-note">到课{{r.row.stats.attended}} · 请假{{r.row.stats.leave}}</div></template></el-table-column><el-table-column v-for="(date,index) in overview.dates" :key="date" :label="lessonLabel(date)" width="155" align="center"><template slot-scope="r"><div :class="'cell-status status-'+r.row.cells[index].status" @click="openCell(r.row,index)">{{r.row.cells[index].text}}</div><div v-if="r.row.cells[index].recorded && r.row.cells[index].review !== '1'" class="overview-note">{{reviewLabel(r.row.cells[index].review)}}</div></template></el-table-column></el-table>
          <p class="overview-note">点击学生查看统计；点击已登记单元格查看该课次明细。到课包含普通、试听与调课到课。</p>
        </template><el-empty v-else-if="!overviewLoading && !overviewError" description="请选择课程查看出勤总览"/>
      </div>
    </div>
    <div v-if="mode==='review'">
    <el-form inline size="small">
      <el-form-item><el-date-picker v-model="classDate" type="date" value-format="yyyy-MM-dd" placeholder="按课次日期筛选" /></el-form-item>
      <el-form-item><el-select v-model="state"><el-option label="待复核" value="0"/><el-option label="待更正" value="2"/><el-option label="已复核" value="1"/></el-select></el-form-item>
      <el-button size="small" type="primary" @click="load">查询</el-button>
    </el-form>
    <el-alert v-if="error" :title="error" type="error" :closable="false"/>
    <el-table :data="displayList" v-loading="loading">
      <el-table-column label="日期" prop="classDate" width="120"/><el-table-column label="课程" prop="courseClassName" min-width="200"/><el-table-column label="教师" prop="teacherName" width="120"/>
      <el-table-column label="时间" prop="timeSlot" width="120"/><el-table-column label="教室" prop="classroomName" width="150"/>
      <el-table-column label="到课 / 名单" width="120"><template slot-scope="s"><b>{{s.row.actualCount || 0}}</b> / {{s.row.totalCount}}</template></el-table-column>
      <el-table-column label="状态" width="100"><template slot-scope="s"><el-tag :type="s.row.reviewStatus==='1'?'success':'warning'">{{s.row.stageText || label(s.row.reviewStatus)}}</el-tag></template></el-table-column>
      <el-table-column label="操作" width="100"><template slot-scope="s"><el-button type="text" @click="open(s.row)">{{s.row.reviewStatus==='0'?'去复核':'查看'}}</el-button></template></el-table-column>
    </el-table>
    </div>
    <el-dialog :title="row ? row.courseClassName + ' · ' + row.classDate : '学生出勤'" :visible.sync="opened" width="760px" :close-on-click-modal="!busy" @closed="close">
      <div v-loading="detailLoading">
        <el-alert v-if="detailError" :title="detailError" type="error" :closable="false"/>
        <template v-if="ready">
          <el-descriptions :column="3" border><el-descriptions-item label="到课">{{detail.review.actualCount || 0}}</el-descriptions-item><el-descriptions-item label="应到名单">{{detail.stats ? detail.stats.expectedCount : detail.review.totalCount}}</el-descriptions-item><el-descriptions-item label="状态">{{detail.stageText || detail.review.stageText || label(detail.review.reviewStatus)}}</el-descriptions-item></el-descriptions>
          <el-alert v-if="detail.review.reviewStatus==='2'" :title="detail.review.reviewReason + '；教师更正后须重新初审和终审'" type="warning" :closable="false"/>
          <p v-if="detail.stats">正常到课 {{detail.stats.attended || 0}} · 试听 {{detail.stats.audited || 0}} · 调课 {{detail.stats.transferred || 0}} · 请假 {{detail.stats.leave || 0}} · 录播 {{detail.stats.recorded || 0}} · 缺席 {{detail.stats.absent || 0}} · 未点名 {{detail.stats.unmarked || 0}}</p><p>终审前为待核实统计，终审通过后才计入正式人数和课酬。</p>
          <el-table :data="detail.details" max-height="340"><el-table-column label="学生" prop="studentName"/><el-table-column label="来源"><template slot-scope="s">{{source(s.row.sourceType)}}</template></el-table-column><el-table-column label="出勤结果"><template slot-scope="s"><el-tag :type="['2','3'].includes(s.row.signStatus)?'info':'success'">{{status(s.row.signStatus)}}</el-tag></template></el-table-column><el-table-column label="备注" prop="remark"/></el-table>
          <el-collapse><el-collapse-item title="操作记录及历史课堂图片" name="history">
            <div v-for="e in history" :key="e.version" class="event"><b>{{action(e.action)}}</b> · {{e.actorName}} · {{e.time}}<p v-if="e.reason">{{e.reason}}</p><el-image v-for="image in (e.images || [])" :key="image" :src="publicImage(image)" :preview-src-list="(e.images || []).map(publicImage)" class="review-image"/><template v-if="e.action==='SUBMIT'"><p>修改前：{{snapshot(e.before)}}</p><p>提交后：{{snapshot(e.after)}}</p></template></div>
            <div v-for="slot in [1,2]" :key="slot" v-if="detail.review['review'+slot+'AdminId']"><p>{{detail.review['review'+slot+'AdminName']}} · {{detail.review['review'+slot+'Time']}}</p><el-image v-for="image in images(slot)" :key="image" :src="image" :preview-src-list="images(slot)" class="review-image"/></div>
          </el-collapse-item></el-collapse>
          <template v-if="canInitial"><p>监控截图（初审必填，至少一张）</p><image-upload v-model="pendingImages" :limit="9"/></template>
        </template>
      </div>
      <div slot="footer"><el-button :disabled="busy" @click="opened=false">关闭</el-button><template v-if="ready && (canInitial || canFinal)"><el-button :disabled="busy" @click="decide('RETURN')">标记异常</el-button><el-button type="primary" :loading="busy" @click="decide('APPROVE')">{{canInitial?'初审通过，提交终审':'终审通过'}}</el-button></template></div>
    </el-dialog>
    <el-dialog :title="selectedStudent ? selectedStudent.studentName + ' · 出勤统计' : '学生出勤统计'" :visible.sync="studentDialog" width="760px" @closed="closeStudent">
      <template v-if="selectedStudent"><el-radio-group v-model="studentScope" size="small" @change="changeStudentScope"><el-radio-button label="course">本课程</el-radio-button><el-radio-button v-if="selectedStudent.linked" label="all">全部课程</el-radio-button></el-radio-group><p class="overview-note">沿用当前日期范围；历史或临时记录不按姓名合并孩子身份。</p><div class="overview-summary"><span><b>{{studentStats.attended || 0}}</b> 正式实到人次</span><span><b>{{studentStats.leave || 0}}</b> 请假</span><span><b>{{studentStats.video || 0}}</b> 录播</span></div><p>正式应到 {{studentStats.expectedCount || 0}} 人次 · 正式实到 {{studentStats.attended || 0}} 人次</p><p>待核实应到 {{studentPendingStats.expectedCount || 0}} 人次 · 实到 {{studentPendingStats.attended || 0}} 人次；正常 {{studentPendingStats.normal || 0}} · 试听 {{studentPendingStats.trial || 0}} · 调课 {{studentPendingStats.transfer || 0}} · 请假 {{studentPendingStats.leave || 0}} · 录播 {{studentPendingStats.video || 0}} · 缺席 {{studentPendingStats.absent || 0}}</p><p class="overview-note">其中试听{{studentStats.trial || 0}}、调课{{studentStats.transfer || 0}} · 已核对{{studentStats.approved || 0}} · 待复核{{studentStats.pending || 0}} · 待更正{{studentStats.returned || 0}}</p><el-alert v-if="studentStats.conflicts" :title="studentStats.conflicts+'个课次有多条记录，未计入确定出勤结果'" type="warning" :closable="false"/><el-alert v-if="studentError" :title="studentError" type="error" :closable="false"/><el-table :data="studentRows" v-loading="studentLoading"><el-table-column label="日期" prop="classDate" width="115"/><el-table-column label="课程" prop="courseClassName"/><el-table-column label="登记姓名" prop="recordedName" width="120"/><el-table-column label="出勤结果" prop="statusText" width="150"/><el-table-column label="复核" width="90"><template slot-scope="r">{{reviewLabel(r.row.reviewStatus)}}</template></el-table-column></el-table><el-pagination layout="prev,pager,next,total" :page-size="30" :current-page="studentPage" :total="studentTotal" @current-change="studentPageChange"/></template>
    </el-dialog>
  </div>
</template>
<script>
import {listReview,getDetail,confirmReview,attendanceCourses,attendanceOverview,attendanceStudent} from '@/api/system/signReview'
import {getToken} from '@/utils/auth'
export default {
  name:'SignReview',
  data(){return {mode:'overview',courseKeyword:'',courses:[],courseId:'',dateRange:null,studentKeyword:'',overview:null,overviewLoading:false,overviewError:'',studentDialog:false,selectedStudent:null,studentScope:'course',studentStats:{},studentPendingStats:{},studentRows:[],studentPage:1,studentTotal:0,studentLoading:false,studentError:'',classDate:null,state:'0',list:[],loading:false,error:'',opened:false,row:null,detail:{details:[],review:{}},detailLoading:false,detailError:'',ready:false,busy:false,pendingImages:''}},
  computed:{canInitial(){return this.detail.canInitialReview===true || this.detail.review.canInitialReview===true},canFinal(){return this.detail.canFinalReview===true || this.detail.review.canFinalReview===true},visibleStudents(){const key=this.studentKeyword.trim();return (this.overview?.rows||[]).filter(r=>!key||r.studentName.includes(key)||String(r.parentPhone||'').includes(key))},displayList(){return this.list.filter(r=>String(r.reviewStatus||'0')===this.state)},history(){return (this.detail.review.history||[]).slice().reverse()}},
  created(){this._alive=true;this.load();this.loadCourses()},
  activated(){this._alive=true;if(this._token!==getToken()){this.close();this.closeStudent();this.load();this.loadCourses()}},
  deactivated(){this.closeStudent();this._overviewVersion=(this._overviewVersion||0)+1;this._coursesVersion=(this._coursesVersion||0)+1;this._alive=false;this._version=(this._version||0)+1;this.opened=false;this.close()},
  beforeDestroy(){this._alive=false;this._overviewVersion=(this._overviewVersion||0)+1;this._coursesVersion=(this._coursesVersion||0)+1;this.closeStudent();this.close()},
  methods:{
    lessonLabel(date){const l=(this.overview?.lessons||[]).find(l=>String(l.classDate).slice(0,10)===date);return date+' · 应到 '+(l?l.expectedCount:'—')+'人'},
    courseLabel(c){return [c.courseClassName||[c.gradeName,c.subjectName].filter(Boolean).join(''),c.teacherName,[c.courseYear,c.termName,c.periodName].filter(Boolean).join(' '),c.timeSlot].filter(Boolean).join(' · ')},
    reviewLabel(v){return {'0':'待审核','1':'已终审 / 历史已确认','2':'待更正'}[v]||''},
    changeMode(){if(this.mode==='overview')this.loadCourses();else this.load()},
    async loadCourses(){const token=getToken(),version=this._coursesVersion=(this._coursesVersion||0)+1;this._overviewVersion=(this._overviewVersion||0)+1;this.closeStudent();this.overview=null;this.overviewLoading=true;this.overviewError='';try{const r=await attendanceCourses(this.courseKeyword);if(!this._alive||token!==getToken()||version!==this._coursesVersion)return;this.courses=r.data||[];if(!this.courses.some(c=>c.scheduleId===this.courseId))this.courseId=this.courses[0]?.scheduleId||'';if(this.courseId)await this.loadOverview()}catch(e){if(token===getToken()&&version===this._coursesVersion)this.overviewError=e.message||'课程加载失败'}finally{if(version===this._coursesVersion)this.overviewLoading=false}},
    async loadOverview(){this.closeStudent();const token=getToken(),version=this._overviewVersion=(this._overviewVersion||0)+1;this.overview=null;this.overviewError='';if(!this.courseId){this.overviewLoading=false;return}this.overviewLoading=true;try{const r=await attendanceOverview(this.courseId,this.dateRange?.[0],this.dateRange?.[1]);if(this._alive&&token===getToken()&&version===this._overviewVersion)this.overview=r.data}catch(e){if(token===getToken()&&version===this._overviewVersion)this.overviewError=e.message||'总览加载失败'}finally{if(version===this._overviewVersion)this.overviewLoading=false}},
    openCell(student,index){if(!student.cells[index].recorded)return;this.open({...this.overview.course,classDate:this.overview.dates[index]})},
    openStudent(student){this.closeStudent();this.selectedStudent=student;this.studentDialog=true;this.studentScope='course';this.studentPage=1;this.loadStudent()},
    closeStudent(){this._studentVersion=(this._studentVersion||0)+1;this.selectedStudent=null;this.studentRows=[];this.studentStats={};this.studentPendingStats={};this.studentTotal=0;this.studentLoading=false;this.studentDialog=false;this.studentError=''},
    changeStudentScope(){this.studentPage=1;this.studentStats={};this.loadStudent()},
    studentPageChange(page){this.studentPage=page;this.loadStudent()},
    async loadStudent(){if(!this.studentDialog||!this.selectedStudent)return;const token=getToken(),version=this._studentVersion=(this._studentVersion||0)+1;this.studentLoading=true;this.studentError='';this.studentRows=[];try{const r=await attendanceStudent({scheduleId:this.courseId,studentKey:this.selectedStudent.studentKey,allCourses:this.studentScope==='all',from:this.dateRange?.[0],to:this.dateRange?.[1],pageNum:this.studentPage});if(!this._alive||!this.studentDialog||token!==getToken()||version!==this._studentVersion)return;this.studentStats=r.data.stats;this.studentPendingStats=r.data.pendingStats||{};this.studentRows=r.data.rows;this.studentTotal=r.data.total}catch(e){if(token===getToken()&&version===this._studentVersion)this.studentError=e.message||'统计加载失败'}finally{if(version===this._studentVersion)this.studentLoading=false}},
    label(v){return {'0':'待审核','1':'已终审 / 历史已确认','2':'待更正'}[v]||'待审核'},
    source(v){return {'1':'报名','2':'试听','3':'调课'}[v]||v},
    status(v){return {'1':'到课','2':'录播','3':'请假','4':'试听到课','5':'调课到课'}[v]||v},
    action(v){return {SUBMIT:'教师提交',APPROVE:'确认复核',RETURN:'标记异常'}[v]||v},
    publicImage(p){return p.startsWith('http')?p:process.env.VUE_APP_BASE_API+p},
    snapshot(rows){return (rows||[]).map(r=>r.studentName+'：'+this.status(r.signStatus)).join('；')||'无'},
    images(slot){return String(this.detail.review['review'+slot+'Images']||'').split(',').filter(Boolean).map(p=>p.startsWith('http')?p:process.env.VUE_APP_BASE_API+p)},
    close(){this._detailVersion=(this._detailVersion||0)+1;this.row=null;this.detail={details:[],review:{}};this.ready=false;this.busy=false;this.pendingImages=''},
    async load(){const token=this._token=getToken(),version=this._version=(this._version||0)+1;this.loading=true;this.error='';this.list=[];try{const r=await listReview(this.classDate);if(this._alive&&token===getToken()&&version===this._version)this.list=r.data||[]}catch(e){if(this._alive&&token===getToken()&&version===this._version)this.error=e.message||'加载失败，请重试'}finally{if(version===this._version)this.loading=false}},
    async open(row){this.close();this.row=row;this.opened=true;this.detailLoading=true;this.detailError='';const token=getToken(),version=this._detailVersion;try{const r=await getDetail(row.scheduleId,row.classDate);if(this._alive&&this.opened&&token===getToken()&&version===this._detailVersion){this.detail=r.data;this.ready=true}}catch(e){if(version===this._detailVersion)this.detailError=e.message||'加载失败'}finally{if(version===this._detailVersion)this.detailLoading=false}},
    async decide(action){
      if(this.busy||!this.ready||!this.opened||!(this.canInitial || this.canFinal))return
      const token=getToken(),version=this._detailVersion,row=this.row,reviewVersion=this.detail.review.reviewVersion||0,images=Array.isArray(this.pendingImages)?this.pendingImages.slice():String(this.pendingImages||'').split(',').filter(Boolean);this.busy=true
      try{if(action==='APPROVE' && this.canInitial && !images.length)throw new Error('初审必须上传至少一张监控截图');let reason;if(action==='RETURN'){const r=await this.$prompt('请说明出勤异常，由教务或管理员更正','标记异常',{inputType:'textarea',inputValidator:v=>!!v&&!!v.trim()&&v.length<=500,inputErrorMessage:'请填写500字以内的原因'});reason=r.value.trim()}else await this.$confirm('确认本阶段审核？终审通过后才进入正式统计和课酬。','确认学生出勤')
        if(!this._alive||!this.opened||token!==getToken()||version!==this._detailVersion)return
        await confirmReview(row.scheduleId,row.classDate,images,{action,reason,reviewVersion,submissionVersion:this.detail.submissionVersion})
        if(!this._alive||token!==getToken()||version!==this._detailVersion)return
        this.$modal.msgSuccess(action==='RETURN'?'已标记异常':'已终审 / 历史已确认');this.opened=false;this.close();this.load()
      }catch(e){if(e!=='cancel'&&e!=='close'&&token===getToken())this.$modal.msgError(e.message||'操作失败，请刷新核对')}finally{if(version===this._detailVersion)this.busy=false}
    }
  }
}
</script>
<style scoped>.overview-summary{display:flex;gap:32px;align-items:center;padding:16px 0;color:#7b889a}.overview-summary b{font-size:28px;color:#263650}.overview-note{font-size:12px;color:#8a95a6;line-height:1.6}.cell-status{cursor:pointer;padding:8px 0}.status-1{color:#27856e}.status-3{color:#b58336}.status-2{color:#8792a3}.status-4{color:#416eaf}.status-5{color:#8562b4}.status-conflict{color:#bd5b56}.status-empty{color:#c6cdd7}.event{padding:12px 0;border-bottom:1px solid #eef0f5}.event p{color:#606b7e;line-height:1.6}.review-image{width:90px;height:90px;margin:8px}.el-alert{margin:12px 0}</style>
