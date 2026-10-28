package com.ruoyi.system.service;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import com.ruoyi.common.core.domain.model.MiniAppEnrollmentBody;
import com.ruoyi.system.mapper.*;
import com.ruoyi.system.service.impl.MiniAppParentServiceImpl;

/** 显式运行于隔离 MySQL 副本，验证唯一当前资料来源及旧请求、财务历史兼容。 */
public class ChildProfileMysqlTest extends ChildCenterMysqlTest {
    @SuppressWarnings("unchecked") void canonicalProfile() throws Exception {
        var personal=new ParentAccountService();inject(personal,"db",db);inject(personal,"schedules",session.getMapper(EduCourseScheduleMapper.class));
        var classes=new MiniAppParentServiceImpl();inject(classes,"tuition",payments);inject(classes,"db",db);inject(classes,"singleService",single);inject(classes,"credits",credits);inject(classes,"schedules",session.getMapper(EduCourseScheduleMapper.class));inject(classes,"miniAppParentMapper",session.getMapper(MiniAppParentMapper.class));
        var original=new LessonCreditService.AccountDraft();original.studentName="唯一档案原名";original.gradeName=grade;original.contactPhone="12345000001";original.requestKey=UUID.randomUUID().toString();
        ScheduleCrudTest.principal(parent,"parent");long a=tx(()->credits.createMine(original));
        var originalAccount=db.one("SELECT * FROM edu_student_lesson_account WHERE account_id=?",a);
        String nextGrade=db.one("SELECT dict_label FROM sys_dict_data WHERE dict_type='edu_grade' AND status='0' AND dict_label<>? ORDER BY dict_sort LIMIT 1",grade).get("dictLabel").toString();
        var edit=new LessonCreditService.AccountDraft();edit.studentName="唯一档案新名";edit.gradeName=nextGrade;edit.contactPhone="12345000002";
        tx(()->{credits.editChild(a,edit,false);return null;});
        assertEquals(originalAccount,db.one("SELECT * FROM edu_student_lesson_account WHERE account_id=?",a),"资料修改不能改写账户创建快照或额度");
        assertEquals(a,tx(()->credits.createMine(original)),"改名后仍能重试原创建请求");
        assertEquals(1L,db.jdbc().queryForObject("SELECT COUNT(*) FROM edu_student_lesson_account WHERE request_key=?",Long.class,originalAccount.get("requestKey")));
        for(var profile:List.of(personal.ownedChild(a),credits.mine().stream().filter(x->String.valueOf(a).equals(x.get("accountId"))).findFirst().orElseThrow(),((List<Map<String,Object>>)personal.detail(parent,false).get("children")).stream().filter(x->String.valueOf(a).equals(x.get("accountId"))).findFirst().orElseThrow())) {
            assertEquals(edit.studentName,profile.get("studentName"));assertEquals(nextGrade,profile.get("gradeName"));assertEquals(edit.contactPhone,profile.get("contactPhone"));assertFalse(profile.containsKey("requestKey"));
        }
        var selected=tx(()->credits.enrollmentChild(parent,a,"过期前端姓名",grade,"00000000000",null));
        assertEquals(edit.studentName,selected.get("studentName"));assertEquals(nextGrade,selected.get("gradeName"));assertEquals(edit.contactPhone,selected.get("contactPhone"));
        ScheduleCrudTest.principal(1,"admin");
        assertEquals(String.valueOf(a),credits.accounts(edit.studentName).get(0).get("accountId"));assertEquals(String.valueOf(a),credits.accounts(edit.contactPhone).get(0).get("accountId"));
        assertTrue(credits.accounts(original.studentName).stream().noneMatch(x->String.valueOf(a).equals(x.get("accountId"))));
        assertTrue(((List<Map<String,Object>>)personal.list(edit.contactPhone,1).get("rows")).stream().anyMatch(x->String.valueOf(parent).equals(x.get("parentId"))));
        assertEquals(edit.studentName,((List<Map<String,Object>>)personal.detail(parent,true).get("children")).stream().filter(x->String.valueOf(a).equals(x.get("accountId"))).findFirst().orElseThrow().get("studentName"));
        adjust(a,"GRANT",5,UUID.randomUUID().toString());
        ScheduleCrudTest.principal(parent,"parent");
        assertEquals(edit.studentName,tx(()->credits.ownForHold(a,parent,edit.studentName,2)).get("studentName"));
        assertThrows(Exception.class,()->tx(()->credits.ownForHold(a,parent,original.studentName,2)));
        long course=publish(teacher,room,date.plusDays(30)),enrollment=enroll(parent,course,date.plusDays(30),original.studentName,a);
        assertEquals(edit.studentName,db.one("SELECT student_name FROM edu_enrollment WHERE enrollment_id=?",enrollment).get("studentName"));balances(a,5,2,0);
        var enrollmentBefore=db.one("SELECT * FROM edu_enrollment WHERE enrollment_id=?",enrollment);
        edit.studentName="再次更名";tx(()->{credits.editChild(a,edit,false);return null;});
        assertEquals(enrollmentBefore,db.one("SELECT * FROM edu_enrollment WHERE enrollment_id=?",enrollment));
        var record=((List<Map<String,Object>>)personal.records(parent,false,"enrollments",1,false,a,false).get("rows")).get(0);
        assertEquals("唯一档案新名",record.get("studentName"));assertEquals("再次更名",record.get("childName"));
        assertTrue(((List<Map<String,Object>>)personal.records(parent,false,"history",1,false,a,false).get("rows")).stream().filter(x->"CREDIT".equals(x.get("source"))).allMatch(x->"再次更名".equals(x.get("title"))));
        long classSchedule=id(db.one("SELECT schedule_id FROM edu_course_schedule WHERE class_mode='1' AND del_flag='0' AND status='0' AND recruit_status='0' ORDER BY schedule_id LIMIT 1"),"scheduleId");
        var body=new MiniAppEnrollmentBody();body.setScheduleId(classSchedule);body.setLessonAccountId(a);body.setStudentName(original.studentName);body.setContactPhone("");tx(()->classes.enroll(body));
        assertEquals("再次更名",db.one("SELECT student_name FROM edu_enrollment WHERE lesson_account_id=? AND schedule_id=?",a,classSchedule).get("studentName"));
        var studentBefore=db.one("SELECT * FROM edu_student WHERE student_id=?",originalAccount.get("studentId"));
        edit.gradeName="无效年级";assertThrows(Exception.class,()->tx(()->{credits.editChild(a,edit,false);return null;}));
        assertEquals(studentBefore,db.one("SELECT * FROM edu_student WHERE student_id=?",originalAccount.get("studentId")));
        ScheduleCrudTest.principal(otherParent,"parent");assertThrows(Exception.class,()->tx(()->credits.enrollmentChild(otherParent,a,"",grade,null,null)));assertThrows(Exception.class,()->tx(()->credits.ownForHold(a,otherParent,"再次更名",1)));assertThrows(Exception.class,()->tx(()->{credits.editChild(a,edit,false);return null;}));
        System.out.println("PASS: canonical student profile across parent/admin/search/new class and one-to-one enrollments, original creation retry, current package identity, unchanged account/enrollment snapshots, invalid edits rollback and owner isolation");
    }
    public static void main(String[] args)throws Exception{var t=new ChildProfileMysqlTest();t.setup();t.childCenter();t.canonicalProfile();}
}
