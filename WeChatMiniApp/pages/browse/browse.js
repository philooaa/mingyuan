const { request } = require('../../utils/request')
const shop = require('../../utils/material-shop')
const { compareGrades } = require('../../utils/grade-order')
const termFilter = require('../../utils/course-term-filter')
Page({
 data:{loading:false,error:'',tab:'courses',keyword:'',courses:[],visibleCourses:[],materials:[],gradeOptions:['全部年级'],subjectOptions:['全部学科'],gradeIndex:0,subjectIndex:0,termOptions:termFilter.options,termIndex:0,expandedId:'',materialDetail:null,loggedIn:false},
 onLoad(){this._sequence=0;this._hidden=false},
 onShow(){
  this._hidden=false
  const loggedIn=!!wx.getStorageSync('token')
  this.setData({loggedIn})
  if(loggedIn){wx.reLaunch({url:'/pages/index/index'});return}
  return this.load()
 },
 onHide(){this._hidden=true;this._sequence=(this._sequence||0)+1},
 onUnload(){this.onHide()},
 onPullDownRefresh(){return this.load().finally(()=>wx.stopPullDownRefresh())},
 async load(){
  const sequence=this._sequence=(this._sequence||0)+1,tab=this.data.tab
  this.setData({loading:true,error:''})
  try{
   const res=await request({url:tab==='courses'?'/miniapp/public/courses':'/miniapp/public/material/list',data:tab==='courses'?{}:{title:this.data.keyword.trim(),pageNum:1,pageSize:100}})
   if(this._hidden||sequence!==this._sequence)return
   if(tab==='courses'){
    const courses=res.data||[]
    const selectedGrade=this.data.gradeOptions[this.data.gradeIndex],selectedSubject=this.data.subjectOptions[this.data.subjectIndex]
    const gradeOptions=['全部年级',...Array.from(new Set(courses.map(c=>c.gradeName).filter(Boolean))).sort(compareGrades)]
    const subjectOptions=['全部学科',...new Set(courses.map(c=>c.subjectName).filter(Boolean))]
    this.setData({courses,gradeOptions,subjectOptions,gradeIndex:Math.max(0,gradeOptions.indexOf(selectedGrade)),subjectIndex:Math.max(0,subjectOptions.indexOf(selectedSubject))});this.filterCourses()
   }else this.setData({materials:((res.data||{}).rows||[]).map(shop.product)})
  }catch(e){if(!this._hidden&&sequence===this._sequence)this.setData({error:e.message||'加载失败，请重试'})}
  finally{if(!this._hidden&&sequence===this._sequence)this.setData({loading:false})}
 },
 chooseTab(e){this.setData({tab:e.currentTarget.dataset.tab,keyword:'',expandedId:'',materialDetail:null});return this.load()},
 onKeyword(e){this.setData({keyword:e.detail.value});if(this.data.tab==='courses')this.filterCourses()},
 search(){return this.data.tab==='courses'?this.filterCourses():this.load()},
 chooseGrade(e){this.setData({gradeIndex:Number(e.detail.value)});this.filterCourses()},
 chooseSubject(e){this.setData({subjectIndex:Number(e.detail.value)});this.filterCourses()},
 chooseTerm(e){const index=Number(e.detail.value);if(!Number.isInteger(index)||index<0||index>=termFilter.options.length)return;this.setData({termIndex:index,expandedId:''});this.filterCourses()},
 filterCourses(){
  const {courses,keyword,gradeIndex,subjectIndex,gradeOptions,subjectOptions}=this.data,k=keyword.trim()
  this.setData({visibleCourses:courses.filter(c=>termFilter.matches(c,this.data.termIndex)&&(!gradeIndex||c.gradeName===gradeOptions[gradeIndex])&&(!subjectIndex||c.subjectName===subjectOptions[subjectIndex])&&(!k||[c.courseClassName,c.gradeName,c.subjectName,c.campusName].some(v=>String(v||'').includes(k))))})
 },
 expandCourse(e){const id=String(e.currentTarget.dataset.id);this.setData({expandedId:this.data.expandedId===id?'':id})},
 async expandMaterial(e){
  const id=String(e.currentTarget.dataset.id),sequence=this._sequence
  try{const res=await request({url:'/miniapp/public/material/'+encodeURIComponent(id)});if(!this._hidden&&sequence===this._sequence)this.setData({materialDetail:shop.product(res.data)})}
  catch(e){if(!this._hidden)this.setData({error:e.message||'资料详情加载失败'})}
 },
 closeDetail(){this.setData({materialDetail:null})},
 login(){wx.navigateTo({url:wx.getStorageSync('token')?'/pages/index/index':'/pages/register/register'})},
 enroll(){wx.navigateTo({url:wx.getStorageSync('token')?'/pages/enrollment/enrollment':'/pages/register/register'})},
 buy(){const id=this.data.materialDetail&&this.data.materialDetail.materialId;if(id)wx.navigateTo({url:wx.getStorageSync('token')?'/pages/material-detail/material-detail?id='+encodeURIComponent(id):'/pages/register/register'})}
})
