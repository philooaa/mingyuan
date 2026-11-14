<template>
  <div style="margin-bottom:20px">
    <el-button v-hasRole="['education','admin','business_admin']" @click="open">补录学员</el-button>
    <el-button v-hasRole="['education','admin','business_admin']" @click="openPending">待绑定学员</el-button>
    <el-dialog title="待绑定学员" :visible.sync="pendingVisible" width="820px"
      :close-on-click-modal="false" :close-on-press-escape="!busy" :show-close="!busy">
      <el-alert v-if="error" :title="error" type="error" :closable="false"/>
      <el-input v-model.trim="pendingQuery" clearable placeholder="搜索学生姓名或课程"/>
      <el-table v-loading="busy" :data="filteredPending" empty-text="暂无匹配的待绑定学员" max-height="480">
        <el-table-column label="学生" prop="studentName" width="150"/>
        <el-table-column label="补录课程">
          <template slot-scope="{ row }">
            <div v-for="(course,index) in row.courses" :key="index">
              {{ [course.courseName,course.courseYear,course.termName,course.periodName,course.teacherName].filter(Boolean).join(' · ') }}
              <div class="pending-detail">{{ [course.startTime,course.endTime].filter(Boolean).join('–') }} · 第{{ course.startLesson }}讲起</div>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="112">
          <template slot-scope="{ row }">
            <el-button type="text" :disabled="busy" @click="bindParent(row,true)">绑定家长</el-button>
          </template>
        </el-table-column>
      </el-table>
      <div slot="footer"><el-button :disabled="busy" @click="loadPending">刷新</el-button></div>
    </el-dialog>
    <el-dialog title="补录学员" :visible.sync="visible" width="820px" :close-on-click-modal="!busy">
      <el-alert v-if="error" :title="error" type="error" :closable="false"/>
      <el-form inline class="roster-filters">
        <el-form-item label="搜索班级">
          <el-input v-model.trim="courseQuery" :disabled="busy" clearable placeholder="课程名称、年份或学期" @input="clearSelection"/>
        </el-form-item>
        <el-form-item label="年份 / 学期">
          <el-select v-model="termFilter" :disabled="busy" clearable placeholder="全部年份 / 学期" @change="clearSelection">
            <el-option v-for="term in terms" :key="term" :label="term" :value="term"/>
          </el-select>
        </el-form-item>
        <el-form-item v-for="filter in courseFilters" :key="filter.field" :label="filter.label">
          <el-select v-model="courseFilterValues[filter.field]" filterable clearable :disabled="busy"
            :placeholder="'全部' + filter.label" @change="clearSelection">
            <el-option v-for="option in filter.options" :key="option" :label="option" :value="option"/>
          </el-select>
        </el-form-item>
        <el-form-item label="班级">
          <el-select v-model="form.scheduleId" filterable placeholder="选择班级" :disabled="busy" @change="changeCourse">
            <el-option v-for="c in filteredCourses" :key="c.scheduleId" :value="String(c.scheduleId)" :label="[c.courseName,c.year,c.termName].filter(Boolean).join(' · ')"/>
          </el-select>
        </el-form-item>
        <el-form-item label="课次">
          <el-select v-model="lessonId" placeholder="选择课次" :disabled="busy || !lessons.length" @change="loadRows">
            <el-option v-for="l in lessons" :key="l.lessonId" :value="l.lessonId" :label="`第${l.lessonNo}讲 · ${l.classDate || '已取消'}${Number(l.locked)?'（锁定）':''}`"/>
          </el-select>
        </el-form-item>
        <el-button type="primary" :disabled="busy || !canAddOffline" @click="addOffline">补录学员</el-button>
      </el-form>
      <el-table :data="rows">
        <el-table-column label="学生" prop="studentName"/>
        <el-table-column label="档案编号" prop="studentId"/>
        <el-table-column label="开始讲次" prop="startLesson"/>
        <el-table-column label="结束讲次" prop="endLesson"/>
        <el-table-column label="操作" min-width="150">
          <template slot-scope="{ row }">
            <el-button v-if="row.membershipId && row.source === 'OFFLINE' && !row.parentId"
              type="text" :disabled="busy" @click="bindParent(row)">绑定家长</el-button>
            <el-button type="text" :disabled="busy" @click="remove(row)">按讲次移出</el-button>
          </template>
        </el-table-column>
      </el-table>
      <el-collapse><el-collapse-item title="关联已有学生档案" name="existing"><el-form label-width="110px" style="margin-top:20px">
        <el-form-item label="关联学生档案"><el-select v-model="form.studentId" filterable remote :remote-method="search" placeholder="输入姓名或手机搜索"><el-option v-for="s in students" :key="s.studentId" :value="String(s.studentId)" :label="`${s.studentName} · 档案${s.studentId} · ${s.contactPhone||''}`"/></el-select></el-form-item>
        <el-form-item label="关联报名编号"><el-input v-model="form.enrollmentId" placeholder="正常报名必填"/></el-form-item>
        <el-form-item label="开始讲次"><el-select v-model="form.startLesson"><el-option v-for="l in lessons" :key="l.lessonId" :value="Number(l.lessonNo)" :label="`第${l.lessonNo}讲 · ${l.classDate}`"/></el-select></el-form-item>
        <el-form-item label="结束讲次"><el-select v-model="form.endLesson" clearable placeholder="可空，到季度结束"><el-option v-for="l in lessons" :key="l.lessonId" :value="Number(l.lessonNo)" :label="`第${l.lessonNo}讲 · ${l.classDate}`"/></el-select></el-form-item>
        <el-form-item label="名单来源"><el-select v-model="form.source"><el-option label="正常报名" value="ENROLLED"/><el-option label="线下/历史插班" value="OFFLINE"/><el-option label="试听" value="TRIAL"/><el-option label="临时调课" value="TRANSFER"/></el-select></el-form-item>
        <el-form-item label="收费状态"><el-select v-model="form.paymentStatus"><el-option label="未收费" value="UNPAID"/><el-option label="历史收费已核对" value="HISTORICAL"/><el-option label="沿用报名记录" value="ENROLLMENT"/></el-select></el-form-item>
        <el-form-item label="调整原因"><el-input v-model="form.reason" maxlength="500"/></el-form-item>
              <el-form-item><el-button type="primary" :loading="busy" :disabled="!lessons.length" @click="save">保存生效范围</el-button></el-form-item>
      </el-form></el-collapse-item></el-collapse>
      <el-dialog title="按讲次移出" :visible.sync="removalVisible" append-to-body width="460px"
        :close-on-click-modal="false" :close-on-press-escape="!busy" :show-close="!busy">
        <el-alert v-if="error" :title="error" type="error" :closable="false"/>
        <p>从所选讲次起，{{ removalRow && removalRow.studentName }}将从本班点名名单移出。此前的点名记录会保留。</p>
        <el-form label-width="90px">
          <el-form-item label="移出讲次">
            <el-select v-model="removalLesson" placeholder="选择从第几讲起移出" :disabled="busy">
              <el-option v-for="lesson in removalLessons" :key="lesson.lessonId" :value="Number(lesson.lessonNo)"
                :label="`第${lesson.lessonNo}讲 · ${lesson.classDate}`"/>
            </el-select>
            <div v-if="!removalLessons.length">当前没有可移出的课次</div>
          </el-form-item>
          <el-form-item label="移出原因">
            <el-input v-model.trim="removalReason" maxlength="255" placeholder="例如录入有误" :disabled="busy"/>
          </el-form-item>
        </el-form>
        <div slot="footer">
          <el-button :disabled="busy" @click="removalVisible=false">取消</el-button>
          <el-button type="primary" :loading="busy" :disabled="!removalLesson || !removalReason"
            @click="confirmRemoval">确认移出</el-button>
        </div>
      </el-dialog>
      <div slot="footer"><el-button :disabled="busy" @click="visible=false">关闭</el-button></div>
    </el-dialog>
  </div>
</template>
<script>
import {rosterCourses,rosterLessons,rosterStudents,rosterList,rosterAdd,rosterRemove,rosterAddOffline,rosterBindParent,rosterPendingParents} from '@/api/system/attendanceRoster'
import {getToken} from '@/utils/auth'
export default {
  name:'AttendanceRoster',data(){return {visible:false,pendingVisible:false,pendingRows:[],pendingQuery:'',removalVisible:false,removalRow:null,removalLesson:null,removalReason:'',courseQuery:'',termFilter:'',courseFilterValues:{teacherName:'',gradeName:'',subjectName:''},busy:false,error:'',courses:[],lessons:[],students:[],rows:[],lessonId:null,form:{scheduleId:'',studentId:'',enrollmentId:'',startLesson:1,endLesson:'',source:'OFFLINE',paymentStatus:'UNPAID',reason:''}}},
  computed:{
    filteredPending(){
      const query=this.pendingQuery.toLowerCase()
      return this.pendingRows.filter(row=>row.studentName.toLowerCase().includes(query)
        || row.courses.some(course=>(course.courseName||'').toLowerCase().includes(query)))
    },
    removalLessons(){
      if(!this.removalRow)return []
      const today=new Intl.DateTimeFormat('sv-SE',{timeZone:'Asia/Shanghai'}).format(new Date())
      const lastLocked=this.lessons.reduce((last,lesson)=>Number(lesson.locked)?Math.max(last,Number(lesson.lessonNo)):last,0)
      return this.lessons.filter(lesson=>lesson.classDate>=today && !Number(lesson.cancelled)
        && Number(lesson.lessonNo)>lastLocked && Number(lesson.lessonNo)>=Number(this.removalRow.startLesson||1)
        && (this.removalRow.endLesson==null||Number(lesson.lessonNo)<=Number(this.removalRow.endLesson)))
    },
    terms(){return [...new Set(this.courses.map(this.courseTerm).filter(Boolean))]},
    courseFilters(){
      return [
        {field:'teacherName',label:'老师姓名'},
        {field:'gradeName',label:'年级'},
        {field:'subjectName',label:'科目'}
      ].map(filter=>({...filter,options:[...new Set(this.courses.map(course=>course[filter.field]).filter(Boolean))]}))
    },
    filteredCourses(){
      const query=this.courseQuery.toLowerCase()
      return this.courses.filter(course=>(!this.termFilter||this.termFilter===this.courseTerm(course))
        && this.courseFilters.every(({field})=>!this.courseFilterValues[field]||course[field]===this.courseFilterValues[field])
        && [course.courseName,this.courseTerm(course)].join(' ').toLowerCase().includes(query))
    },
    selectedLesson(){return this.lessons.find(lesson=>lesson.lessonId===this.lessonId)},
    canAddOffline(){
      const lesson=this.selectedLesson
      return this.form.scheduleId && lesson && lesson.classDate && !Number(lesson.locked) && !Number(lesson.cancelled)
    }
  },
  methods:{
    courseTerm(course){return [course.year,course.termName].filter(Boolean).join(' ')},
    clearSelection(){
      if(this.filteredCourses.some(course=>String(course.scheduleId)===this.form.scheduleId))return
      this.form.scheduleId=''
      this.lessonId=null
      this.lessons=[]
      this.rows=[]
    },
    openPending(){
      this.pendingVisible=true
      this._token=getToken()
      this.pendingRows=[]
      this.pendingQuery=''
      this.loadPending()
    },
    loadPending(){
      return this.run(async()=>{
        const result=await rosterPendingParents()
        if(this.current()&&this.pendingVisible)this.pendingRows=result.data||[]
      })
    },
    current(){return (this.visible||this.pendingVisible)&&this._token===getToken()},
    async run(fn){if(this.busy)return;this.busy=true;this.error='';try{await fn()}catch(e){if(this.current())this.error=e.message||'操作失败'}finally{this.busy=false}},
    open(){this.visible=true;this._token=getToken();this.rows=[];this.lessons=[];this.form.scheduleId='';this.run(async()=>{const r=await rosterCourses();if(this.current())this.courses=r.data||[]})},
    changeCourse(){this.run(async()=>{this.rows=[];this.lessons=[];this.lessonId=null;const r=await rosterLessons(this.form.scheduleId);if(!this.current())return;this.lessons=r.data||[];this.lessonId=this.lessons[0]?.lessonId;await this.fetchRows()})},
    async fetchRows(){if(!this.lessonId)return;const r=await rosterList(this.form.scheduleId,this.lessonId);if(this.current())this.rows=r.data||[]},loadRows(){this.run(()=>this.fetchRows())},
    async search(keyword){const sequence=this._search=(this._search||0)+1;try{const r=await rosterStudents(keyword);if(this.current()&&sequence===this._search)this.students=r.data||[]}catch(e){if(this.current())this.error=e.message}},
    async promptInput(message,title,options){
      try {
        const {value}=await this.$prompt(message,title,options)
        return value.trim()
      } catch (error) {
        if(error!=='cancel'&&error!=='close')throw error
        return null
      }
    },
    addOffline(){
      if(!this.canAddOffline)return
      const {scheduleId}=this.form
      const {lessonNo,classDate}=this.selectedLesson
      return this.run(async()=>{
        const studentName=await this.promptInput(`从第${lessonNo}讲（${classDate}）开始点名。请输入学生姓名。`,'补录学员',{
          inputValidator:value=>typeof value==='string'&&value.trim().length>0&&value.trim().length<=30,
          inputErrorMessage:'请输入30字以内的学生姓名'
        })
        if(studentName===null||!this.current())return
        await rosterAddOffline({scheduleId,studentName,startLesson:Number(lessonNo)})
        if(this.current()){
          await this.fetchRows()
          if(this.current())this.$modal.msgSuccess('已补录学员')
        }
      })
    },
    bindParent(row, pending=false){
      return this.run(async()=>{
        const phone=await this.promptInput(`请核实「${row.studentName}」（档案 ${row.studentId}）家长的注册手机号。`,'绑定家长',{
          inputValidator:value=>typeof value==='string'&&/^1[3-9][0-9]{9}$/.test(value.trim()),
          inputErrorMessage:'请输入11位家长手机号'
        })
        if(phone===null||!this.current())return
        await rosterBindParent(row.studentId,{phone})
        if(this.current()){
          if(pending)this.pendingRows=this.pendingRows.filter(student=>student.studentId!==row.studentId)
          else await this.fetchRows()
          if(this.current())this.$modal.msgSuccess('已绑定家长')
        }
      })
    },
    save(){this.run(async()=>{const f=this.form;if(!f.studentId||!f.scheduleId||!f.reason.trim())throw new Error('请选择课程和学生并填写原因');if(f.source==='ENROLLED'&&!f.enrollmentId)throw new Error('正常报名须关联报名编号');if(f.endLesson&&Number(f.endLesson)<Number(f.startLesson))throw new Error('结束讲次不能早于开始讲次');await rosterAdd({...f,endLesson:f.endLesson||null});if(this.current()){await this.fetchRows();this.$modal.msgSuccess('已保存')}})},
    remove(row){
      this.error=''
      this.removalRow=row
      this.removalReason=''
      const selected=Number(this.selectedLesson && this.selectedLesson.lessonNo)
      this.removalLesson=this.removalLessons.some(lesson=>Number(lesson.lessonNo)===selected)?selected:null
      this.removalVisible=true
    },
    confirmRemoval(){
      if(!this.removalLesson||!this.removalReason)return
      return this.run(async()=>{
        await rosterRemove(this.removalRow,{
          scheduleId:this.form.scheduleId,effectiveLesson:this.removalLesson,reason:this.removalReason
        })
        if(this.current()){
          this.removalVisible=false
          await this.fetchRows()
          if(this.current())this.$modal.msgSuccess('已按讲次移出')
        }
      })
    }
  }
}
</script>

<style scoped>
.pending-detail { color: #65758b; font-size: 12px; }
.roster-filters { padding: 16px 16px 0; background: #f5f7fa; border-radius: 6px; }
.el-table { margin: 12px 0 20px; }
</style>
