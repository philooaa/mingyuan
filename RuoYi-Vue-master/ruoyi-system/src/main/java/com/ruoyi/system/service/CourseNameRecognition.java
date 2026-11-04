package com.ruoyi.system.service;

import java.util.*;
import com.ruoyi.common.exception.ServiceException;

/** Read-only recognition. No account creation or fuzzy teacher matching. */
public final class CourseNameRecognition {
    private CourseNameRecognition() {}
    private static String norm(Object value) { return value==null?"":value.toString().replaceAll("[\\s\\u3000]+", ""); }
    @SuppressWarnings("unchecked")
    public static Map<String,Object> recognize(String raw, Map<String,Object> dimensions) {
        String text=norm(raw); if(text.isEmpty() || text.length()>100) throw new ServiceException("请填写100字以内的课程名称");
        List<String> warnings=new ArrayList<>(); Map<String,Object> result=new LinkedHashMap<>();
        String grade=prefix(text,(List<Map<String,Object>>)dimensions.get("grades"));
        if(grade==null) { warnings.add("未识别年级，请按年级、学科、班型、教师的顺序填写");result.put("warnings",warnings);return result; }
        text=text.substring(norm(grade).length()); result.put("gradeName",grade);
        String subject=prefix(text,(List<Map<String,Object>>)dimensions.get("subjects"));
        if(subject==null) { warnings.add("未识别学科，请手动选择");result.put("warnings",warnings);return result; }
        text=text.substring(norm(subject).length()); result.put("subjectName",subject);
        List<Map<String,Object>> matched=new ArrayList<>(); int longest=0;
        List<Map<String,Object>> teachers=(List<Map<String,Object>>)dimensions.get("teachers");
        if(teachers!=null) for(var t:teachers) {
            int len=0;
            for(Object value:Arrays.asList(t.get("realName"),t.get("nickName"))) {String name=norm(value);if(name.length()>=2 && !name.matches("[0-9]+") && text.endsWith(name))len=Math.max(len,name.length());}
            if(len>longest){longest=len;matched.clear();} if(len>0 && len==longest)matched.add(t);
        }
        if(longest>0) {
            String suffix=text.substring(text.length()-longest);text=text.substring(0,text.length()-longest);
            List<Map<String,Object>> candidates=new ArrayList<>(); Set<String> seen=new HashSet<>();
            for(var teacher:matched) {Object id=teacher.get("userId");if(id==null || !seen.add(id.toString()))continue;Map<String,Object> candidate=new LinkedHashMap<>();candidate.put("id",id.toString());candidate.put("name",teacher.get("realName"));candidate.put("nickName",teacher.get("nickName"));candidates.add(candidate);}
            result.put("teacherCandidates",candidates);
            if(candidates.size()==1) {result.put("teacherId",candidates.get(0).get("id"));result.put("teacherName",candidates.get(0).get("name"));}
            else warnings.add("教师“"+suffix+"”存在多个账号，请手动选择");
        } else warnings.add("未找到名称末尾完全匹配的教师，请手动选择");
        if(!text.isEmpty()) {
            if(longest>0 || text.endsWith("班")) {
                result.put("classType",text);
                if(!text.endsWith("班")) warnings.add("班型候选可能包含其他说明，请核对");
            } else warnings.add("剩余内容无法确定为班型，请手动填写："+text);
        }
        result.put("warnings",warnings);return result;
    }
    private static String prefix(String text,List<Map<String,Object>> rows) {
        if(rows==null)return null;String best=null;
        for(var row:rows) { Object label=row.get("label");String n=norm(label);if(!n.isEmpty() && text.startsWith(n) && (best==null || n.length()>norm(best).length()))best=label.toString(); }
        return best;
    }
}
