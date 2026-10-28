package com.ruoyi.system.finance;

import static com.ruoyi.system.finance.FinanceRules.*;
import com.alibaba.fastjson2.JSON;

import com.ruoyi.system.shop.ShopRepository;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.util.*;
import org.springframework.stereotype.Service;

@Service
public class CouponService {
  private final ShopRepository db;

  public CouponService(ShopRepository db) {
    this.db = db;
  }

  public void admin(long actor) {
    require(
        db.jdbc()
                .queryForObject(
                    "SELECT COUNT(*) FROM sys_user u JOIN sys_user_role ur ON ur.user_id=u.user_id"
                        + " JOIN sys_role r ON r.role_id=ur.role_id WHERE u.user_id=? AND"
                        + " u.status='0' AND u.del_flag='0' AND r.role_key IN ('admin','business_admin') AND r.status='0'"
                        + " AND r.del_flag='0'",
                    Integer.class,
                    actor)
            > 0,
        "FORBIDDEN: 管理员权限不足");
  }

  public String username(long actor) {
    return str(db.one("SELECT user_name FROM sys_user WHERE user_id=?", actor), "userName");
  }

  public static Map<String, Object> page(
      ShopRepository db, String select, String count, List<Object> args, Map<String, String> q) {
    int number = integer(q.getOrDefault("pageNum", "1"), 1, 1000000),
        size = integer(q.getOrDefault("pageSize", "20"), 1, 100);
    long total =
        Objects.requireNonNull(db.jdbc().queryForObject(count, Long.class, args.toArray()));
    List<Object> paged = new ArrayList<>(args);
    paged.add(size);
    paged.add((number - 1) * size);
    return Map.of("rows", db.rows(select + " LIMIT ? OFFSET ?", paged.toArray()), "total", total);
  }

  public Map<String, Object> listTemplates(Map<String, String> q) {
    String where = " WHERE 1=1";
    List<Object> a = new ArrayList<>();
    if (!str(q, "couponName").isEmpty()) {
      where += " AND coupon_name LIKE ?";
      a.add("%" + str(q, "couponName") + "%");
    }
    if (!str(q, "templateStatus").isEmpty()) {
      where += " AND template_status=?";
      a.add(q.get("templateStatus"));
    }
    var result=page(
        db,
        "SELECT * FROM edu_coupon_template" + where + " ORDER BY template_id DESC",
        "SELECT COUNT(*) FROM edu_coupon_template" + where,
        a,
        q);
    decoratePage(result);return result;
  }

  /** Existing schedules persist the dictionary subject label; store that same canonical value. */
  public static List<String> subjects(Object raw) {
    if(raw==null || "".equals(raw))return List.of();
    Object value=raw instanceof String s?JSON.parse(s):raw;
    require(value instanceof Collection<?>,"适用学科必须是列表");
    var names=new TreeSet<String>();for(var item:(Collection<?>)value){require(item instanceof String,"学科名称无效");String name=item.toString().trim();require(!name.isEmpty()&&name.length()<=30,"学科名称无效");names.add(name);}
    require(names.size()<=30,"适用学科过多");return List.copyOf(names);
  }
  private static Map<String,Object> decorate(Map<String,Object> row){row.put("subjectNames",subjects(row.get("subjectNames")));row.put("templateRulesHash",rulesHash(row));return row;}
  private static String rulesHash(Map<String,Object> row){var rules=new TreeMap<String,Object>();for(String field:List.of("templateId","couponName","description","discountAmount","minSpendAmount","scopeScheduleId","courseScope","validFrom","validUntil"))rules.put(field,str(row,field));rules.put("subjectNames",subjects(row.get("subjectNames")));return hash(rules);}
  @SuppressWarnings("unchecked") private static void decoratePage(Map<String,Object> result){((List<Map<String,Object>>)result.get("rows")).forEach(CouponService::decorate);}
  private static final String SUBJECT_MATCH=" AND (t.subject_names IS NULL OR JSON_LENGTH(t.subject_names)=0 OR JSON_CONTAINS(t.subject_names,JSON_QUOTE((SELECT cs.subject_name FROM edu_course_schedule cs WHERE cs.schedule_id=?))))";
  private static final String COURSE_MATCH=" AND (t.course_scope IN ('LEGACY','ALL') OR t.course_scope=?)";
  private String billScope(Map<String,Object> bill){
    if(CreditPackageService.isPackage(bill))return "ONE_TO_ONE";
    if(bill.get("scheduleId")==null)return "UNKNOWN";
    return "2".equals(str(db.one("SELECT class_mode FROM edu_course_schedule WHERE schedule_id=?",bill.get("scheduleId")),"classMode"))?"ONE_TO_ONE":"CLASS";
  }
  public Map<String,Object> options(long actor) {
    admin(actor);
    return Map.of("subjects",db.rows("SELECT dict_label subject_name FROM sys_dict_data WHERE dict_type='edu_subject' AND status='0' ORDER BY dict_sort,dict_code"),
      "schedules",db.rows("SELECT s.schedule_id,s.course_class_name,s.subject_name,s.grade_name,COALESCE(NULLIF(TRIM(u.user_name),''),s.teacher_name) teacher_name,s.start_date,s.start_time,s.end_time,c.campus_name,c.classroom_name FROM edu_course_schedule s LEFT JOIN sys_user u ON u.user_id=s.teacher_id LEFT JOIN edu_classroom c ON c.classroom_id=s.classroom_id WHERE s.del_flag='0' AND s.status='0' ORDER BY s.schedule_id DESC"));
  }

  public Map<String, Object> saveTemplate(long actor, Long template, Map<String, Object> b) {
    admin(actor);
    return db.transactions()
        .execute(
            tx -> {
              Map<String, Object> old =
                  template == null
                      ? Map.of()
                      : db.one(
                          "SELECT * FROM edu_coupon_template WHERE template_id=? FOR UPDATE",
                          template);
              Map<String, Object> merged = new HashMap<>(old);
              merged.putAll(b);
              String name = reason(merged, "couponName", 100),
                  description = str(merged, "description");
              require(description.length() <= 500, "说明过长");
              BigDecimal discount = money(merged.get("discountAmount")),
                  min = money(merged.getOrDefault("minSpendAmount", "0"));
              require(discount.signum() > 0, "券面额必须大于0");
              Timestamp from = time(merged.get("validFrom")),
                  until = time(merged.get("validUntil"));
              require(until.after(from), "失效时间必须晚于生效时间");
              int total = integer(merged.get("totalQuantity"), 1, Integer.MAX_VALUE);
              String status = str(merged, "templateStatus");
              String courseScope=str(merged,"courseScope");if(courseScope.isEmpty())courseScope="LEGACY";
              require(Set.of("LEGACY","ONE_TO_ONE","CLASS","ALL").contains(courseScope),"请选择有效的适用课程类型");
              var selectedSubjects=subjects(merged.get("subjectNames"));
              int issued=old.isEmpty()?0:integer(old.get("issuedQuantity"),0,Integer.MAX_VALUE);
              if(issued==0)for(String subject:selectedSubjects)require(db.jdbc().queryForObject("SELECT COUNT(*) FROM sys_dict_data WHERE dict_type='edu_subject' AND status='0' AND dict_label=?",Integer.class,subject)>0,"请选择有效的授课学科");
              require(Set.of("DRAFT", "ACTIVE", "PAUSED").contains(status), "模板状态无效");
              Long scope =
                  str(merged, "scopeScheduleId").isEmpty()
                      ? null
                      : id(merged.get("scopeScheduleId"));
              if(!"LEGACY".equals(courseScope)){require(str(b,"scopeScheduleId").isEmpty(),"新优惠券按课程类型适用，不能再限定某门课程");scope=null;}
              if (scope != null)
                db.one("SELECT schedule_id FROM edu_course_schedule WHERE schedule_id=?", scope);
              if(issued==0 && scope!=null && !selectedSubjects.isEmpty())require(selectedSubjects.contains(str(db.one("SELECT subject_name FROM edu_course_schedule WHERE schedule_id=?",scope),"subjectName")),"指定课程不在所选学科范围内");
              var rules =
                  values(
                      "coupon_name",
                      name,
                      "description",
                      description,
                      "discount_amount",
                      discount,
                      "min_spend_amount",
                      min,
                      "subject_names",
                      selectedSubjects.isEmpty()?null:JSON.toJSONString(selectedSubjects),
                      "scope_schedule_id",
                      scope,
                      "course_scope",
                      courseScope,
                      "valid_from",
                      from,
                      "valid_until",
                      until,
                      "total_quantity",
                      total,
                      "template_status",
                      status,
                      "update_by",
                      username(actor),
                      "update_time",
                      now());
              long savedId;
              if (template == null) {
                rules.put("template_code", code("CT"));
                rules.put("create_by", username(actor));
                savedId = db.insert("edu_coupon_template", rules);
              } else {
                require(total >= issued, "发行上限不得小于已发数量");
                if (issued > 0) {
                  require(
                      name.equals(str(old, "couponName"))
                          && description.equals(str(old, "description"))
                          && discount.compareTo(money(old.get("discountAmount"))) == 0
                          && min.compareTo(money(old.get("minSpendAmount"))) == 0
                          && selectedSubjects.equals(subjects(old.get("subjectNames")))
                          && courseScope.equals(str(old,"courseScope"))
                          && Objects.equals(
                              scope == null ? "" : scope.toString(), str(old, "scopeScheduleId"))
                          && from.equals(time(old.get("validFrom")))
                          && until.equals(time(old.get("validUntil"))),
                      "发券后规则冻结，仅允许启停或提高发行上限");
                  require(
                      total >= integer(old.get("totalQuantity"), 1, Integer.MAX_VALUE), "发行上限不能降低");
                }
                rules.put("version", integer(old.get("version"), 0, Integer.MAX_VALUE) + 1);
                db.update("edu_coupon_template", "template_id", template, rules);
                savedId = template;
              }
              return decorate(db.one("SELECT * FROM edu_coupon_template WHERE template_id=?", savedId));
            });
  }

  public Map<String, Object> issue(long template, long actor, Map<String, Object> b) {
    return issue(template, actor, b, List.of());
  }

  /** Only ReferralService constructs these persisted source identities; never read them from the ordinary HTTP body. */
  public record ReferralRecipient(long rewardId, long parentId) {}

  public Map<String, Object> issueReferral(long template, long actor, Map<String, Object> b,
      List<ReferralRecipient> sources) {
    require(!sources.isEmpty(), "请选择推荐奖励接收人");
    return issue(template, actor, b, List.copyOf(sources));
  }

  private Map<String, Object> issued(long actor, String key, List<ReferralRecipient> sources) {
    var rows = sources.isEmpty()
        ? db.rows("SELECT * FROM edu_user_coupon WHERE granted_by=? AND grant_request_key=? ORDER BY parent_id", actor, key)
        : db.rows("SELECT * FROM edu_user_coupon WHERE referral_reward_id IN (" + placeholders(sources.size()) + ") ORDER BY referral_reward_id",
            sources.stream().map(ReferralRecipient::rewardId).toArray());
    return Map.of("rows", rows, "total", rows.size());
  }

  private Map<String, Object> issue(long template, long actor, Map<String, Object> b,
      List<ReferralRecipient> sources) {
    admin(actor);
    String key = key(b), reason = reason(b, "reason", 255);
    require(b.get("parentIds") instanceof Collection<?>, "请选择家长");
    SortedSet<Long> parents = new TreeSet<>();
    for (Object p : (Collection<?>) b.get("parentIds")) parents.add(id(p));
    require(!parents.isEmpty() && parents.size() <= 100, "每批请选择1至100名家长");
    var recipients = sources.isEmpty() ? parents.stream().map(p -> new ReferralRecipient(0,p)).toList() : sources;
    require(recipients.size() <= 100 && new HashSet<>(recipients.stream().map(ReferralRecipient::parentId).toList()).equals(parents), "奖励接收名单无效");
    var filters = recipientFilters(b);
    Map<String, Object> snapshot =
        new HashMap<>(
            Map.of("templateId", String.valueOf(template), "parentIds", parents, "reason", reason));
    // Preserve hashes of legacy requests that did not carry recipient filters.
    filters.forEach(
        (name, value) -> {
          if (!value.isEmpty()) snapshot.put(name, value);
        });
    String hash = hash(snapshot);
    if(b.containsKey("templateRulesHash")){snapshot.put("templateRulesHash",str(b,"templateRulesHash"));hash=hash(snapshot);}
    if (!sources.isEmpty()) {
      snapshot.put("referralRewards", sources.stream().map(s -> s.rewardId()+":"+s.parentId()).sorted().toList());
      hash = hash(snapshot);
    }
    final String requestHash = hash;
    return db.transactions()
        .execute(
            tx -> {
              db.one("SELECT user_id FROM sys_user WHERE user_id=? FOR UPDATE", actor);
              @SuppressWarnings("unchecked")
              var existing = (List<Map<String,Object>>) issued(actor,key,sources).get("rows");
              if (!existing.isEmpty()) {
                require(
                    existing.size()==recipients.size() && existing.stream().allMatch(r -> requestHash.equals(r.get("grantRequestHash"))),
                    "IDEMPOTENCY_CONFLICT: 同一批次编号内容不同");
                return Map.of("rows", existing, "total", existing.size());
              }
              var t =
                  db.one(
                      "SELECT * FROM edu_coupon_template WHERE template_id=? FOR UPDATE", template);
              require(
                  "ACTIVE".equals(t.get("templateStatus"))
                      && time(t.get("validUntil")).after(now()),
                  "模板未启用或已过期");
              if(b.containsKey("templateRulesHash"))require(rulesHash(t).equals(str(b,"templateRulesHash")),"模板规则已变化，请重新选择并预览后发放");
              List<Object> recipientArgs = new ArrayList<>(parents);
              String recipientWhere =
                  PARENT_WHERE
                      + " AND u.user_id IN ("
                      + placeholders(parents.size())
                      + ")"
                      + recipientMatch(filters, recipientArgs);
              require(
                  db.jdbc()
                          .queryForObject(
                              "SELECT COUNT(*) FROM sys_user u" + recipientWhere,
                              Integer.class,
                              recipientArgs.toArray())
                      == parents.size(),
                  "接收人必须是有效家长账号且仍匹配报名年级／学科筛选，整批未发放");
              require(
                  db.jdbc()
                          .update(
                              "UPDATE edu_coupon_template SET"
                                  + " issued_quantity=issued_quantity+?,version=version+1,update_by=?,update_time=?"
                                  + " WHERE template_id=? AND issued_quantity+?<=total_quantity",
                              recipients.size(),
                              username(actor),
                              now(),
                              template,
                              recipients.size())
                      == 1,
                  "优惠券发行额度不足");
              for (var recipient : recipients) {
                long parent = recipient.parentId();
                if (sources.isEmpty()) require(
                    db.rows(
                            "SELECT user_coupon_id FROM edu_user_coupon WHERE template_id=? AND"
                                + " parent_id=?",
                            template,
                            parent)
                        .isEmpty(),
                    "该模板已给所选家长发放过，整批未发放");
                if (!sources.isEmpty()) db.one("SELECT reward_id FROM edu_referral_reward WHERE reward_id=? AND parent_id=? AND template_id=?",recipient.rewardId(),parent,template);
                var couponValues = values(
                        "coupon_code",
                        code("UC"),
                        "template_id",
                        template,
                        "parent_id",
                        parent,
                        "coupon_status",
                        "AVAILABLE",
                        "valid_from",
                        time(t.get("validFrom")),
                        "valid_until",
                        time(t.get("validUntil")),
                        "granted_by",
                        actor,
                        "grant_request_key",
                        sources.isEmpty() ? key : "REFERRAL-" + recipient.rewardId(),
                        "grant_request_hash",
                        requestHash,
                        "grant_reason",
                        reason,
                        "create_by",
                        username(actor),
                        "update_by",
                        username(actor));
                if (!sources.isEmpty()) couponValues.put("referral_reward_id",recipient.rewardId());
                db.insert("edu_user_coupon",couponValues);
              }
              return issued(actor,key,sources);
            });
  }

  public Map<String, Object> revoke(long coupon, long actor, Map<String, Object> b) {
    admin(actor);
    String reason = reason(b, "reason", 255);
    return db.transactions()
        .execute(
            tx -> {
              var c =
                  db.one("SELECT * FROM edu_user_coupon WHERE user_coupon_id=? FOR UPDATE", coupon);
              require("AVAILABLE".equals(c.get("couponStatus")), "仅可撤回未使用且未锁定的券");
              db.update(
                  "edu_user_coupon",
                  "user_coupon_id",
                  coupon,
                  values(
                      "coupon_status",
                      "REVOKED",
                      "revoked_by",
                      actor,
                      "revoked_time",
                      now(),
                      "revoke_reason",
                      reason,
                      "version",
                      integer(c.get("version"), 0, Integer.MAX_VALUE) + 1,
                      "update_by",
                      username(actor),
                      "update_time",
                      now()));
              if(c.get("referralRewardId") != null) {
                var event = com.alibaba.fastjson2.JSON.toJSONString(Map.of("action","REVOKE","actorId",String.valueOf(actor),"reason",reason,"time",now().toString()));
                db.jdbc().update("UPDATE edu_referral_reward SET history=JSON_ARRAY_APPEND(history,'$',CAST(? AS JSON)) WHERE reward_id=?",event,c.get("referralRewardId"));
              }
              return db.one("SELECT * FROM edu_user_coupon WHERE user_coupon_id=?", coupon);
            });
  }

  static final String COUPONS =
      "SELECT"
          + " c.*,t.coupon_name,t.description,t.discount_amount,t.min_spend_amount,t.scope_schedule_id,t.course_scope,t.subject_names,u.phonenumber parent_phone,"
          + FinanceRules.parentNameSql("u")
          + " parent_name,CASE WHEN c.coupon_status='AVAILABLE' AND c.valid_until<=NOW(3) THEN"
          + " 'EXPIRED' WHEN c.coupon_status='AVAILABLE' AND c.valid_from>NOW(3) THEN 'NOT_STARTED'"
          + " ELSE c.coupon_status END display_status FROM edu_user_coupon c JOIN"
          + " edu_coupon_template t ON c.template_id=t.template_id JOIN sys_user u ON"
          + " u.user_id=c.parent_id";

  public Map<String, Object> myCoupons(long parent, Map<String, String> q) {
    return grants(parent, false, q);
  }

  public Map<String, Object> grants(long actor, boolean admin, Map<String, String> q) {
    if (admin) admin(actor);
    String where = " WHERE 1=1";
    List<Object> a = new ArrayList<>();
    if (!admin) {
      where += " AND c.parent_id=?";
      a.add(actor);
    }
    if (!str(q, "couponStatus").isEmpty()) {
      where += " AND c.coupon_status=?";
      a.add(str(q, "couponStatus"));
    }
    if (!str(q, "displayStatus").isEmpty()) {
      String d = str(q, "displayStatus");
      if (d.equals("EXPIRED"))
        where += " AND c.coupon_status='AVAILABLE' AND c.valid_until<=NOW(3)";
      else if (d.equals("AVAILABLE"))
        where +=
            " AND c.coupon_status='AVAILABLE' AND c.valid_from<=NOW(3) AND c.valid_until>NOW(3)";
      else {
        where += " AND c.coupon_status=?";
        a.add(d);
      }
    }
    if (!str(q, "parentId").isEmpty() && admin) {
      where += " AND c.parent_id=?";
      a.add(id(q.get("parentId")));
    }
    if(admin&&!str(q,"parentPhone").isBlank()){where+=" AND u.phonenumber LIKE ?";a.add(phone(q.get("parentPhone"))+"%");}
    if (!str(q, "enrollmentId").isEmpty()) {
      var e =
          db.one(
              "SELECT * FROM edu_enrollment WHERE enrollment_id=?"
                  + (admin ? "" : " AND parent_id=?"),
              admin
                  ? new Object[] {id(q.get("enrollmentId"))}
                  : new Object[] {id(q.get("enrollmentId")), actor});
      where +=
          " AND c.parent_id=? AND c.coupon_status='AVAILABLE' AND c.valid_from<=NOW(3) AND"
              + " c.valid_until>NOW(3) AND t.min_spend_amount<=? AND (t.scope_schedule_id IS NULL"
              + " OR t.scope_schedule_id=?)";
      a.add(e.get("parentId"));
      a.add(CreditPackageService.couponBase(e).get("originalAmount"));
      a.add(e.get("scheduleId"));
      where+=SUBJECT_MATCH;a.add(e.get("scheduleId"));
      where+=COURSE_MATCH;a.add(billScope(e));
    }
    if (!str(q, "templateId").isBlank()) {
      where += " AND c.template_id=?";
      a.add(id(q.get("templateId")));
    }
    if(!str(q,"couponName").isBlank()){where+=" AND t.coupon_name LIKE ?";a.add("%"+str(q,"couponName")+"%");}
    if (!str(q, "parentName").isBlank()) {
      where += " AND " + FinanceRules.parentSearchSql("u") + " LIKE ?";
      a.add("%" + q.get("parentName") + "%");
    }
    var result=page(
        db,
        COUPONS + where + " ORDER BY c.user_coupon_id DESC",
        "SELECT COUNT(*) FROM edu_user_coupon c JOIN edu_coupon_template t ON"
            + " c.template_id=t.template_id JOIN sys_user u ON u.user_id=c.parent_id"
            + where,
        a,
        q);
    decoratePage(result);return result;
  }

  public List<Map<String, Object>> available(Map<String, Object> bill) {
    if (bill.get("originalAmount") == null) return List.of();
    if(CreditPackageService.isPackage(bill) && bill.get("enrollmentId")!=null){
      if(bill.get("userCouponId")==null)return List.of();
      var fixed=db.rows(COUPONS+" WHERE c.user_coupon_id=? AND c.parent_id=?",bill.get("userCouponId"),bill.get("parentId"));fixed.forEach(CouponService::decorate);return fixed;
    }
    bill=CreditPackageService.couponBase(bill);
    var result=db.rows(
        COUPONS
            + " WHERE c.parent_id=? AND c.coupon_status='AVAILABLE' AND c.valid_from<=? AND"
            + " c.valid_until>? AND t.min_spend_amount<=? AND (t.scope_schedule_id IS NULL OR"
            + " t.scope_schedule_id=?)"+SUBJECT_MATCH+COURSE_MATCH+" ORDER BY c.user_coupon_id",
        bill.get("parentId"),
        now(),
        now(),
        bill.get("originalAmount"),
        bill.get("scheduleId"),bill.get("scheduleId"),billScope(bill));
    result.forEach(CouponService::decorate);return result;
  }

  public BigDecimal discount(long coupon, Map<String, Object> e, boolean lock) {
    boolean purchase=CreditPackageService.isPackage(e);
    e=CreditPackageService.couponBase(e);
    var c =
        db.one(
            "SELECT c.*,t.discount_amount,t.min_spend_amount,t.scope_schedule_id,t.course_scope,t.subject_names FROM"
                + " edu_user_coupon c JOIN edu_coupon_template t ON t.template_id=c.template_id"
                + " WHERE c.user_coupon_id=?"
                + (lock ? " FOR UPDATE" : ""),
            coupon);
    boolean held=purchase && e.get("enrollmentId")!=null && "LOCKED".equals(c.get("couponStatus"))
        && str(e,"enrollmentId").equals(str(c,"lockedEnrollmentId")) && str(e,"userCouponId").equals(str(c,"userCouponId"));
    require(
        str(c, "parentId").equals(str(e, "parentId"))
            && (held || ("AVAILABLE".equals(c.get("couponStatus"))
                && !time(c.get("validFrom")).after(now()) && time(c.get("validUntil")).after(now())))
            && money(e.get("originalAmount")).compareTo(money(c.get("minSpendAmount"))) >= 0
            && (c.get("scopeScheduleId") == null
                || str(c, "scopeScheduleId").equals(str(e, "scheduleId"))),
        "COUPON_NOT_AVAILABLE: 优惠券不属于当前家长、不可用或不适用本课程");
    var selectedSubjects=subjects(c.get("subjectNames"));
    require(Set.of("LEGACY","ALL",billScope(e)).contains(str(c,"courseScope")),"COUPON_COURSE_TYPE_MISMATCH: 优惠券不适用于本课程类型");
    require(!purchase || (selectedSubjects.isEmpty() && c.get("scopeScheduleId")==null),"通用课包仅支持无指定课程、无学科限制的优惠券");
    if(!selectedSubjects.isEmpty())require(selectedSubjects.contains(str(db.one("SELECT subject_name FROM edu_course_schedule WHERE schedule_id=?",e.get("scheduleId")),"subjectName")),"COUPON_SUBJECT_MISMATCH: 优惠券不适用于本课程学科");
    return money(c.get("discountAmount")).min(money(e.get("originalAmount")));
  }

  public Map<String, Object> parents(long actor, Map<String, String> q) {
    admin(actor);
    var filters = recipientFilters(q);
    List<Object> a = new ArrayList<>();
    String where = PARENT_WHERE + recipientMatch(filters, a);
    if(!str(q,"parentPhone").isBlank()){where+=" AND u.phonenumber LIKE ?";a.add(phone(q.get("parentPhone"))+"%");}
    String search = text(q.getOrDefault("q", q.getOrDefault("search", "")));
    require(search.length() <= 64, "家长搜索条件最多64字");
    if (!search.isBlank()) {
      where += " AND (u.user_name LIKE ? OR u.nick_name LIKE ? OR u.phonenumber LIKE ?)";
      a.add("%" + search + "%");
      a.add("%" + search + "%");
      a.add("%" + search + "%");
    }
    var result =
        page(
            db,
            "SELECT u.user_id parent_id,"
                + FinanceRules.parentNameSql("u")
                + " parent_name,u.user_name,u.phonenumber parent_phone FROM sys_user u"
                + where
                + " ORDER BY u.user_id",
            "SELECT COUNT(*) FROM sys_user u" + where,
            a,
            q);
    @SuppressWarnings("unchecked")
    var rows = (List<Map<String, Object>>) result.get("rows");
    if (!rows.isEmpty()) {
      List<Object> detailArgs = new ArrayList<>();
      String detailWhere = enrollmentMatch(filters, detailArgs);
      rows.forEach(row -> detailArgs.add(row.get("parentId")));
      var details =
          db.rows(
              "SELECT e.parent_id,e.enrollment_id,e.student_name,s.grade_name,s.subject_name"
                  + ENROLLMENT_FROM
                  + " WHERE "
                  + detailWhere
                  + " AND e.parent_id IN ("
                  + placeholders(rows.size())
                  + ") ORDER BY e.parent_id,e.enrollment_id",
              detailArgs.toArray());
      Map<String, List<Map<String, Object>>> byParent = new HashMap<>();
      for (var detail : details) {
        String parent = str(detail, "parentId");
        detail.remove("parentId");
        byParent.computeIfAbsent(parent, ignored -> new ArrayList<>()).add(detail);
      }
      rows.forEach(
          row ->
              row.put("matchedChildren", byParent.getOrDefault(str(row, "parentId"), List.of())));
    }
    return result;
  }
  public static String phone(String value){String p=value==null?"":value.trim();require(p.matches("[0-9]{3,11}"),"请输入3至11位家长手机号");return p;}

  private static final String PARENT_WHERE =
      " WHERE u.status='0' AND u.del_flag='0' AND EXISTS(SELECT 1 FROM sys_user_role ur JOIN"
          + " sys_role r ON r.role_id=ur.role_id WHERE ur.user_id=u.user_id AND"
          + " r.role_key='parent' AND r.status='0' AND r.del_flag='0')";
  private static final String ENROLLMENT_FROM =
      " FROM edu_enrollment e JOIN edu_course_schedule s ON s.schedule_id=e.schedule_id";

  private static Map<String, String> recipientFilters(Map<String, ?> input) {
    String grade = str(input, "gradeName"), subject = str(input, "subjectName");
    require(grade.length() <= 20 && subject.length() <= 20, "报名年级／学科筛选最多20字");
    return Map.of("gradeName", grade, "subjectName", subject);
  }

  private static String enrollmentMatch(Map<String, String> filters, List<Object> args) {
    String where =
        "e.enrollment_status IN ('报名成功','1','已确认') AND e.status='0' AND e.del_flag='0'"
            + " AND s.status='0' AND s.del_flag='0'";
    if (!filters.get("gradeName").isEmpty()) {
      where += " AND s.grade_name=?";
      args.add(filters.get("gradeName"));
    }
    if (!filters.get("subjectName").isEmpty()) {
      where += " AND s.subject_name=?";
      args.add(filters.get("subjectName"));
    }
    return where;
  }

  private static String recipientMatch(Map<String, String> filters, List<Object> args) {
    if (filters.values().stream().allMatch(String::isEmpty)) return "";
    return " AND EXISTS(SELECT 1"
        + ENROLLMENT_FROM
        + " WHERE e.parent_id=u.user_id AND "
        + enrollmentMatch(filters, args)
        + ")";
  }

  private static String placeholders(int size) {
    return String.join(",", Collections.nCopies(size, "?"));
  }
}
