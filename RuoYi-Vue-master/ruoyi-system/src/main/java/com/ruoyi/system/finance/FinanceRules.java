package com.ruoyi.system.finance;

import com.alibaba.fastjson2.JSON;
import com.ruoyi.common.exception.ServiceException;
import java.math.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;

/** Shared trust-boundary rules; unknown historical money must stay unknown. */
public final class FinanceRules {
  private FinanceRules() {}

  public static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

  /** Role guard complements each controller's specific menu permission. */
  public static void tuitionStaff(com.ruoyi.system.shop.ShopRepository db, long actor) {
    require(
        db.jdbc()
                .queryForObject(
                    "SELECT COUNT(*) FROM sys_user u JOIN sys_user_role ur ON ur.user_id=u.user_id"
                        + " JOIN sys_role r ON r.role_id=ur.role_id WHERE u.user_id=? AND"
                        + " u.status='0' AND u.del_flag='0' AND r.role_key IN ('admin','business_admin','education')"
                        + " AND r.status='0' AND r.del_flag='0'",
                    Integer.class,
                    actor)
            > 0,
        "FORBIDDEN: 教务或管理员权限不足");
  }

  public static String text(Object v) {
    return v == null ? "" : String.valueOf(v).trim();
  }

  /**
   * 家长姓名的 SQL 表达式（真名优先，真名不是中文名时退回中文昵称）。{@code alias} 是 sys_user 的表别名。
   *
   * <p>为什么不能直接用 {@code nick_name}：家长账号的 {@code nick_name} 存的是报名表里的「微信昵称」。
   * 195 个导入家长里有 20 个是经办老师/课程顾问的微信（「数学管老师」「课程顾问周老师」等），
   * 其中 195 金孝泽 / 295 杨泽远 更是直接等于教师 112 的真名「王文晶」——
   * 直接显示会让人误以为该家长就是那位老师。
   *
   * <p>为什么不能只用 {@code user_name}：手机号注册的家长 {@code user_name} 是
   * {@code mp_<手机号>_<hash>} 机器名（如 437/443/444），纯数字手机号的账号（如 100）也一样不适合当姓名。
   *
   * <p>对现有数据的结果：195→金孝泽、295→杨泽远、117→潘奕博、142→杨昕诺、
   * 437→家长3793、394→测试学生01、100→13800000000。
   */
  public static String parentNameSql(String alias) {
    return "CASE WHEN TRIM(COALESCE("
        + alias
        + ".user_name,''))='' THEN TRIM(COALESCE("
        + alias
        + ".nick_name,''))"
        + " WHEN "
        + alias
        + ".user_name REGEXP '[一-龥]' THEN TRIM("
        + alias
        + ".user_name)"
        + " WHEN TRIM(COALESCE("
        + alias
        + ".nick_name,''))<>'' AND "
        + alias
        + ".nick_name REGEXP '[一-龥]' THEN TRIM("
        + alias
        + ".nick_name)"
        + " ELSE TRIM("
        + alias
        + ".user_name) END";
  }

  /**
   * 家长姓名搜索表达式，与展示口径完全一致（搜什么显示什么）。
   *
   * <p>刻意<b>不</b>把 {@code nick_name} 拼进来：那样搜「王文晶」会命中 195/295 两个家长，
   * 而列表里显示的是「金孝泽 / 杨泽远」，反而让人以为又认错人了。
   * 需要按微信昵称找人请去「用户管理」。
   */
  public static String parentSearchSql(String alias) {
    return "(" + parentNameSql(alias) + ")";
  }

  public static String str(Map<String, ?> m, String key) {
    return text(m.get(key));
  }

  public static void require(boolean ok, String message) {
    if (!ok) throw new ServiceException(message);
  }

  public static long id(Object v) {
    try {
      long n = Long.parseLong(text(v));
      if (n < 1) throw new IllegalArgumentException();
      return n;
    } catch (Exception e) {
      throw new ServiceException("ID无效");
    }
  }

  public static BigDecimal money(Object v) {
    try {
      BigDecimal n = new BigDecimal(text(v)).setScale(2, RoundingMode.UNNECESSARY);
      if (n.signum() < 0 || n.compareTo(new BigDecimal("99999999.99")) > 0)
        throw new ArithmeticException();
      return n;
    } catch (Exception e) {
      throw new ServiceException("金额必须为非负数且最多两位小数");
    }
  }

  public static boolean mockAllowed(String[] profiles, String mode) {
    return Arrays.stream(profiles).anyMatch(p -> p.equals("local") || p.equals("test"))
        && Arrays.stream(profiles).noneMatch(p -> p.equals("prod") || p.equals("production"))
        && "mock".equalsIgnoreCase(mode);
  }

  public static Timestamp now() {
    return Timestamp.valueOf(LocalDateTime.now(ZONE));
  }

  public static Timestamp time(Object value) {
    try {
      return Timestamp.valueOf(
          LocalDateTime.parse(text(value), DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
    } catch (Exception e) {
      throw new ServiceException("时间格式必须为yyyy-MM-dd HH:mm:ss");
    }
  }

  public static String code(String prefix) {
    return prefix + UUID.randomUUID().toString().replace("-", "");
  }

  public static String key(Map<String, Object> body) {
    String key = str(body, "idempotencyKey");
    require(key.matches("[A-Za-z0-9_-]{1,64}"), "请提供有效幂等请求编号");
    return key;
  }

  public static String reason(Map<String, Object> b, String name, int max) {
    String s = str(b, name);
    require(!s.isBlank() && s.length() <= max, "请填写有效的原因／说明（最多" + max + "字）");
    return s;
  }

  private static Object canonical(Object value) {
    if (value instanceof Map<?, ?> map) {
      Map<String, Object> sorted = new TreeMap<>();
      map.forEach((k, v) -> sorted.put(String.valueOf(k), canonical(v)));
      return sorted;
    }
    if (value instanceof Collection<?> items)
      return items.stream().map(FinanceRules::canonical).toList();
    return value;
  }

  public static String hash(Object value) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256")
                  .digest(JSON.toJSONString(canonical(value)).getBytes(StandardCharsets.UTF_8)));
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  public static Map<String, Object> dto(Map<String, Object> source) {
    Map<String, Object> result = new LinkedHashMap<>();
    for (var entry : source.entrySet()) {
      Object value = entry.getValue();
      if (value instanceof Number
          && Set.of(
                  "recordedBy",
                  "confirmedBy",
                  "priceConfirmedBy",
                  "issuedBy",
                  "voidBy",
                  "grantedBy",
                  "revokedBy")
              .contains(entry.getKey())) value = value.toString();
      else if (value instanceof Map<?, ?> map) value = dto((Map<String, Object>) map);
      else if (value instanceof Collection<?> list)
        value =
            list.stream()
                .map(v -> v instanceof Map<?, ?> m ? dto((Map<String, Object>) m) : v)
                .toList();
      result.put(entry.getKey(), value);
    }
    return result;
  }

  public static String fingerprint(long business, Map<String, Object> b, String... keys) {
    Map<String, Object> normalized = new TreeMap<>();
    normalized.put("businessId", String.valueOf(business));
    for (String k : keys) normalized.put(k, text(b.get(k)));
    return hash(normalized);
  }

  public static Map<String, Object> values(Object... pairs) {
    Map<String, Object> m = new LinkedHashMap<>();
    for (int i = 0; i < pairs.length; i += 2) m.put((String) pairs[i], pairs[i + 1]);
    return m;
  }

  public static int integer(Object v, int min, int max) {
    try {
      int n = new BigDecimal(text(v)).intValueExact();
      if (n < min || n > max) throw new ArithmeticException();
      return n;
    } catch (Exception e) {
      throw new ServiceException("请输入范围内整数");
    }
  }
}
