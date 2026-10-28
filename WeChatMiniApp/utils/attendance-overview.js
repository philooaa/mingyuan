const reviewLabels={'0':'待复核','1':'已复核','2':'待更正'}
function row(item){
 const tail=String(item.parentPhone||'').slice(-4)
 return {...item,phoneHint:tail?'家长尾号 '+tail:'未留家长电话',identityHint:item.temporary?'临时学员记录':item.linked?'':'历史报名未关联孩子',cells:(item.cells||[]).map((c,index)=>({...c,columnKey:index,reviewText:c.recorded?(reviewLabels[String(c.review)]||''):''}))}
}
function courses(items){return (items||[]).map(c=>({...c,label:[c.courseClassName||[c.gradeName,c.subjectName].filter(Boolean).join(''),c.teacherName,[c.courseYear,c.termName,c.periodName].filter(Boolean).join(' '),c.timeSlot].filter(Boolean).join(' · ')}))}
function summary(item){return {...item,rowKey:`${item.scheduleId}:${item.classDate}`,reviewText:reviewLabels[String(item.reviewStatus)]||''}}
function filter(rows,keyword){const k=String(keyword||'').trim();return !k?rows:rows.filter(r=>String(r.studentName||'').includes(k)||String(r.parentPhone||'').includes(k))}
function mergeLessons(oldRows,newRows){return [...new Map([...oldRows,...newRows].map(r=>[`${r.scheduleId}:${r.classDate}`,r])).values()]}
module.exports={row,courses,summary,filter,mergeLessons}
