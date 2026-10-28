package com.ruoyi.system.service;

import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.common.utils.SecurityUtils;
import com.ruoyi.system.shop.ShopRepository;

/** 双端共用学生课次账户；余额变更与报名由同一数据库事务维护。 */
@Service
public class LessonCreditService {
    @Autowired private ShopRepository db;
    private static final ZoneId ZONE=ZoneId.of("Asia/Shanghai");
    // 当前资料只来自学生档案。账户旧资料列仅保存创建请求，供幂等重试核对。
    static final String CHILD_JOIN=" JOIN edu_student st ON st.student_id=a.student_id AND st.parent_id=a.parent_id AND st.del_flag='0' ";
    private static final String GRADE_LABEL="(SELECT d.dict_label FROM sys_dict_data d WHERE d.dict_type='edu_grade' AND d.dict_value=st.grade_code ORDER BY d.status,d.dict_code LIMIT 1)";
    static final String CHILD_FIELDS="a.account_id,a.parent_id,a.student_id,st.student_name,"+GRADE_LABEL+" AS grade_name,st.student_phone AS contact_phone,a.remaining_credits,a.reserved_credits,a.consumed_credits,a.remaining_credits-a.reserved_credits AS available_credits,a.request_key,a.create_by,a.create_time,a.update_by,a.update_time";
    public static void admin() { check(SecurityUtils.isAdmin() || SecurityUtils.hasRole("admin"),"只有管理员可以管理交易和课次"); }
    public static class AccountDraft { public Long parentId; public String studentName; public String gradeName; public String contactPhone; public String requestKey; }
    public static class Adjustment { public String action; public Integer quantity; public String reason; public String requestKey; }
    public List<Map<String,Object>> parents(String keyword) {
        admin(); String like=like(keyword);
        return db.rows("SELECT u.user_id,u.user_name,u.nick_name,u.phonenumber FROM sys_user u WHERE u.status='0' AND u.del_flag='0' AND EXISTS(SELECT 1 FROM sys_user_role ur JOIN sys_role r ON r.role_id=ur.role_id WHERE ur.user_id=u.user_id AND r.role_key='parent' AND r.status='0' AND r.del_flag='0') AND (u.user_name LIKE ? OR u.nick_name LIKE ? OR u.phonenumber LIKE ?) ORDER BY u.user_id LIMIT 50",like,like,like);
    }
    public List<Map<String,Object>> accounts(String keyword) {
        admin(); String like=like(keyword);
        return db.rows("SELECT "+CHILD_FIELDS+",u.user_name AS parent_name,u.phonenumber AS parent_phone FROM edu_student_lesson_account a"+CHILD_JOIN+" JOIN sys_user u ON u.user_id=a.parent_id WHERE st.student_name LIKE ? OR st.student_phone LIKE ? OR u.user_name LIKE ? OR u.phonenumber LIKE ? ORDER BY a.account_id DESC LIMIT 100",like,like,like,like);
    }
    public List<Map<String,Object>> mine() {
        parent();
        var children=db.rows("SELECT "+CHILD_FIELDS+" FROM edu_student_lesson_account a"+CHILD_JOIN+" WHERE a.parent_id=? ORDER BY a.account_id",SecurityUtils.getUserId());
        children.forEach(child->child.remove("requestKey"));return children;
    }
    @Transactional(rollbackFor=Exception.class)
    public long create(AccountDraft body) {
        admin(); return createChild(body,"ADMIN:");
    }
    /** 家长管理和报名新增共用同一孩子身份；只按请求键去重，绝不按姓名合并。 */
    @Transactional(rollbackFor=Exception.class)
    public long createMine(AccountDraft body) {
        parent(); check(body != null,"请填写孩子资料"); body.parentId=SecurityUtils.getUserId();
        return createChild(body,"CHILD:");
    }
    private long createChild(AccountDraft body,String prefix) {
        check(body != null && body.parentId != null,"请选择家长账号");
        String name=text(body.studentName,30,"学生姓名"),grade=text(body.gradeName,20,"年级");
        check(body.contactPhone == null || body.contactPhone.matches("[0-9]{0,11}"),"联系电话最多11位数字");
        String key=prefix+SecurityUtils.getUserId()+":"+rawKey(body.requestKey);
        db.one("SELECT user_id FROM sys_user WHERE user_id=? AND status='0' AND del_flag='0' AND EXISTS(SELECT 1 FROM sys_user_role ur JOIN sys_role r ON r.role_id=ur.role_id WHERE ur.user_id=sys_user.user_id AND r.role_key='parent' AND r.status='0' AND r.del_flag='0') FOR UPDATE",body.parentId);
        check(!db.rows("SELECT dict_code FROM sys_dict_data WHERE dict_type='edu_grade' AND dict_label=? AND status='0'",grade).isEmpty(),"请选择有效年级");
        var old=db.rows("SELECT account_id,parent_id,student_name,grade_name,contact_phone FROM edu_student_lesson_account WHERE request_key=?",key);
        if (!old.isEmpty()) { var a=old.get(0); check(body.parentId.equals(id(a.get("parentId"))) && name.equals(a.get("studentName")) && grade.equals(a.get("gradeName")) && Objects.equals(body.contactPhone,a.get("contactPhone")),"重复请求内容不一致"); return id(a.get("accountId")); }
        var values=new LinkedHashMap<String,Object>(); values.put("parent_id",body.parentId); values.put("student_name",name); values.put("grade_name",grade); values.put("contact_phone",body.contactPhone);
        values.put("request_key",key); values.put("create_by",SecurityUtils.getUsername()); values.put("create_time",LocalDateTime.now(ZONE));
        var student=new LinkedHashMap<String,Object>(); student.put("parent_id",body.parentId);student.put("student_name",name);student.put("student_phone",body.contactPhone);
        student.put("grade_code",gradeCode(grade));student.put("create_by",SecurityUtils.getUsername());student.put("update_by",SecurityUtils.getUsername());
        long studentId=db.insert("edu_student",student);values.put("student_id",studentId);
        return db.insert("edu_student_lesson_account",values);
    }
    private String gradeCode(String grade) {
        var rows=db.rows("SELECT dict_value FROM sys_dict_data WHERE dict_type='edu_grade' AND dict_label=? AND status='0'",grade);
        check(rows.size()==1,"年级字典需唯一配置"); return rows.get(0).get("dictValue").toString();
    }
    /** 必须在报名事务内调用；账户行锁同时保护归属、改名、删除与额度。 */
    public Map<String,Object> enrollmentChild(Long parentId,Long accountId,String name,String grade,String phone,String requestKey) {
        check(parentId != null && (parentId.equals(SecurityUtils.getUserId()) || SecurityUtils.isAdmin() || SecurityUtils.hasRole("admin")),"无权使用其他家长的孩子");
        if(accountId == null) {
            var draft=new AccountDraft(); draft.parentId=parentId; draft.studentName=name; draft.gradeName=grade; draft.contactPhone=phone; draft.requestKey=requestKey;
            accountId=createChild(draft,"CHILD:");
        }
        return currentProfile(db.one("SELECT * FROM edu_student_lesson_account WHERE account_id=? AND parent_id=? FOR UPDATE",accountId,parentId));
    }
    /** 先锁账户再锁所属学生；后续报名快照和课包校验均使用同一份当前资料。 */
    private Map<String,Object> currentProfile(Map<String,Object> account) {
        var student=db.one("SELECT st.student_name,st.student_phone AS contact_phone,"+GRADE_LABEL+" AS grade_name FROM edu_student st WHERE st.student_id=? AND st.parent_id=? AND st.del_flag='0' FOR UPDATE",account.get("studentId"),account.get("parentId"));
        account.putAll(student);return account;
    }
    @Transactional(rollbackFor=Exception.class)
    public void editChild(Long accountId,AccountDraft body,boolean administrator) {
        if(administrator) admin(); else parent();
        check(body != null,"请填写孩子资料");
        // 与新增、删除采用家长→账户锁顺序；普通编辑不能变更家长或余额。
        var before=db.one("SELECT parent_id FROM edu_student_lesson_account WHERE account_id=?",accountId);
        Long owner=id(before.get("parentId")); check(administrator || owner.equals(SecurityUtils.getUserId()),"无权修改其他家长的孩子");
        db.one("SELECT user_id FROM sys_user WHERE user_id=? FOR UPDATE",owner);
        var account=db.one("SELECT * FROM edu_student_lesson_account WHERE account_id=? AND parent_id=? FOR UPDATE",accountId,owner);
        String name=text(body.studentName,30,"学生姓名"),grade=text(body.gradeName,20,"年级");
        check(body.contactPhone == null || body.contactPhone.matches("[0-9]{0,11}"),"联系电话最多11位数字");
        check(!db.rows("SELECT dict_code FROM sys_dict_data WHERE dict_type='edu_grade' AND dict_label=? AND status='0'",grade).isEmpty(),"请选择有效年级");
        var student=new LinkedHashMap<String,Object>();student.put("student_name",name);student.put("student_phone",body.contactPhone);student.put("grade_code",gradeCode(grade));student.put("update_by",SecurityUtils.getUsername());student.put("update_time",LocalDateTime.now(ZONE));
        db.one("SELECT student_id FROM edu_student WHERE student_id=? AND parent_id=? AND del_flag='0' FOR UPDATE",account.get("studentId"),owner);
        db.update("edu_student","student_id",id(account.get("studentId")),student);
        // 不同步改写账户创建快照，也不回写既有报名、考勤或收据。
    }
    @Transactional(rollbackFor=Exception.class)
    public void deleteChild(Long accountId,boolean administrator) {
        if(administrator) admin(); else parent();
        var before=db.one("SELECT parent_id FROM edu_student_lesson_account WHERE account_id=?",accountId);
        Long owner=id(before.get("parentId")); check(administrator || owner.equals(SecurityUtils.getUserId()),"无权删除其他家长的孩子");
        db.one("SELECT user_id FROM sys_user WHERE user_id=? FOR UPDATE",owner);
        var a=db.one("SELECT * FROM edu_student_lesson_account WHERE account_id=? AND parent_id=? FOR UPDATE",accountId,owner);
        check(number(a,"remainingCredits")==0 && number(a,"reservedCredits")==0 && number(a,"consumedCredits")==0,"孩子仍有课次余额或消耗记录，不能删除");
        check(db.rows("SELECT enrollment_id FROM edu_enrollment WHERE lesson_account_id=? LIMIT 1",accountId).isEmpty()
            && db.rows("SELECT event_id FROM edu_lesson_credit_event WHERE account_id=? LIMIT 1",accountId).isEmpty(),"孩子已有报名或交易历史，须保留资料，不能删除");
        check(db.rows("SELECT enrollment_id FROM edu_enrollment WHERE student_id=? LIMIT 1",a.get("studentId")).isEmpty(),"孩子已有报名历史，不能删除");
        db.jdbc().update("DELETE FROM edu_student_lesson_account WHERE account_id=? AND parent_id=?",accountId,owner);
        db.update("edu_student","student_id",id(a.get("studentId")),Map.of("del_flag","2","update_by",SecurityUtils.getUsername(),"update_time",LocalDateTime.now(ZONE)));
    }
    private static void parent() { check(SecurityUtils.hasRole("parent"),"请以家长身份管理孩子信息"); }
    @Transactional(rollbackFor=Exception.class)
    public void adjust(Long accountId,Adjustment body) {
        admin(); check(body != null && ("GRANT".equals(body.action) || "WITHDRAW".equals(body.action)),"请选择发放或收回");
        check(body.quantity != null && body.quantity>0 && body.quantity<=100000,"课次数量应为1至100000的整数");
        String reason=text(body.reason,255,"操作原因");
        var account=db.one("SELECT * FROM edu_student_lesson_account WHERE account_id=? FOR UPDATE",accountId);
        change(account,null,body.action,body.quantity,key(body.requestKey),reason);
    }
    public Map<String,Object> ownForHold(Long accountId,Long parentId,String studentName,int required) {
        check(accountId != null,"请选择孩子的课包账户");
        var account=db.one("SELECT * FROM edu_student_lesson_account WHERE account_id=? FOR UPDATE",accountId);
        check(parentId.equals(id(account.get("parentId"))),"课包不属于当前家长");
        currentProfile(account);
        check(Objects.equals(studentName,account.get("studentName")),"学生姓名须与所选课包账户一致");
        check(number(account,"remainingCredits")-number(account,"reservedCredits")>=required,"可用课次不足，请联系管理员发放课次或选择单次付费");
        return account;
    }
    public void hold(Map<String,Object> account,Long enrollmentId,int quantity) { change(account,enrollmentId,"HOLD",quantity,"HOLD:"+enrollmentId,"报名暂占"); }
    public void release(Map<String,Object> enrollment) { fromEnrollment(enrollment,"RELEASE"); }
    public void consume(Map<String,Object> enrollment) { fromEnrollment(enrollment,"CONSUME"); }
    private void fromEnrollment(Map<String,Object> e,String action) {
        if (!"P".equals(e.get("settlementMethod"))) return;
        Long accountId=id(e.get("lessonAccountId")),enrollmentId=id(e.get("enrollmentId"));
        check(accountId != null && e.get("lessonCredits") != null,"课包报名缺少原始额度快照，请核对");
        var account=db.one("SELECT * FROM edu_student_lesson_account WHERE account_id=? FOR UPDATE",accountId);
        check(id(e.get("parentId")).equals(id(account.get("parentId"))),"课包归属不一致");
        change(account,enrollmentId,action,number(e,"lessonCredits"),action+":"+enrollmentId,action.equals("CONSUME") ? "教师确认完成" : "报名取消或拒绝释放");
    }
    private void change(Map<String,Object> a,Long enrollmentId,String action,int quantity,String key,String reason) {
        Long accountId=id(a.get("accountId"));
        // 账户行锁串行化同一学生的所有变更；不锁不存在的流水键，避免跨账户间隙锁互等。
        var previous=db.rows("SELECT account_id,enrollment_id,action,quantity,reason FROM edu_lesson_credit_event WHERE request_key=?",key);
        if(!previous.isEmpty()) { var old=previous.get(0); check(accountId.equals(id(old.get("accountId"))) && Objects.equals(enrollmentId,id(old.get("enrollmentId"))) && action.equals(old.get("action")) && quantity==number(old,"quantity") && Objects.equals(reason,old.get("reason")),"重复请求内容不一致"); return; }
        int remaining=number(a,"remainingCredits"),reserved=number(a,"reservedCredits"),consumed=number(a,"consumedCredits");
        if(action.equals("GRANT")) remaining=Math.addExact(remaining,quantity);
        if(action.equals("WITHDRAW")) { check(remaining-reserved>=quantity,"收回不能超过可用课次，已预约额度需先由老师处理"); remaining-=quantity; }
        if(action.equals("HOLD")) { check(remaining-reserved>=quantity,"可用课次不足"); reserved+=quantity; }
        if(action.equals("RELEASE") || action.equals("CONSUME")) {
            check(!db.rows("SELECT event_id FROM edu_lesson_credit_event WHERE enrollment_id=? AND action='HOLD'",enrollmentId).isEmpty(),"找不到原预约暂占记录");
            check(db.rows("SELECT event_id FROM edu_lesson_credit_event WHERE enrollment_id=? AND action IN ('RELEASE','CONSUME')",enrollmentId).isEmpty(),"该预约额度已释放或消耗");
            check(reserved>=quantity,"暂占余额不一致，请核对操作记录"); reserved-=quantity;
            if(action.equals("CONSUME")) { remaining-=quantity; consumed=Math.addExact(consumed,quantity); }
        }
        check(remaining>=reserved && reserved>=0 && remaining<=10000000,"课次余额超出允许范围");
        db.update("edu_student_lesson_account","account_id",accountId,Map.of("remaining_credits",remaining,"reserved_credits",reserved,"consumed_credits",consumed,"update_by",SecurityUtils.getUsername(),"update_time",LocalDateTime.now(ZONE)));
        var v=new LinkedHashMap<String,Object>(); v.put("account_id",accountId); v.put("enrollment_id",enrollmentId); v.put("action",action); v.put("quantity",quantity);
        v.put("remaining_after",remaining); v.put("reserved_after",reserved); v.put("consumed_after",consumed); v.put("request_key",key); v.put("reason",reason); v.put("actor_id",SecurityUtils.getUserId()); v.put("actor_name",SecurityUtils.getUsername()); v.put("create_time",LocalDateTime.now(ZONE)); db.insert("edu_lesson_credit_event",v);
    }
    public List<Map<String,Object>> history(Long accountId,boolean administrator) {
        if(administrator) admin(); else db.one("SELECT account_id FROM edu_student_lesson_account WHERE account_id=? AND parent_id=?",accountId,SecurityUtils.getUserId());
        return db.rows("SELECT event_id,enrollment_id,action,quantity,remaining_after,reserved_after,consumed_after,actor_name,reason,create_time FROM edu_lesson_credit_event WHERE account_id=? ORDER BY event_id DESC LIMIT 200",accountId);
    }
    private static String rawKey(String raw) { check(raw != null && raw.matches("[A-Za-z0-9_-]{16,64}"),"请选择已有孩子或重新打开新增孩子窗口"); return raw; }
    private static String key(String raw) { return "ADMIN:"+SecurityUtils.getUserId()+":"+rawKey(raw); }
    private static String text(String raw,int max,String label) { check(raw != null && !raw.isBlank() && raw.trim().length()<=max,label+"不能为空且不能超过"+max+"字"); return raw.trim(); }
    private static String like(String raw) { check(raw == null || raw.length()<=100,"搜索词过长"); return "%"+(raw == null ? "" : raw.trim())+"%"; }
    private static int number(Map<String,Object> row,String key) { return Integer.parseInt(row.get(key).toString()); }
    private static Long id(Object value) { return value == null ? null : Long.valueOf(value.toString()); }
    private static void check(boolean ok,String message) { if(!ok) throw new ServiceException(message); }
}
