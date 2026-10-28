package com.ruoyi.framework.web.service;

import java.security.SecureRandom;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import com.ruoyi.common.exception.ServiceException;

/** Short-lived, single-use phone challenges. Development codes never enable production login. */
@Service
public class MiniAppPhoneCodeService {
    private static final SecureRandom RANDOM = new SecureRandom();
    private final StringRedisTemplate redis;
    private final Environment environment;
    private final String mode;
    private static final DefaultRedisScript<Long> ISSUE = new DefaultRedisScript<>("""
        if redis.call('EXISTS', KEYS[2]) == 1 then return -1 end
        local count = redis.call('INCR', KEYS[4])
        if count == 1 then redis.call('EXPIRE', KEYS[4], 60) end
        if count > 10 then return -2 end
        redis.call('SET', KEYS[2], '1', 'EX', 60)
        redis.call('SET', KEYS[1], ARGV[1], 'EX', 300)
        redis.call('DEL', KEYS[3])
        return 1
        """, Long.class);
    private static final DefaultRedisScript<Long> VERIFY = new DefaultRedisScript<>("""
        local expected = redis.call('GET', KEYS[1])
        if not expected then return 0 end
        if expected == ARGV[1] then
          redis.call('DEL', KEYS[1], KEYS[2])
          return 1
        end
        local failures = redis.call('INCR', KEYS[2])
        if failures == 1 then redis.call('EXPIRE', KEYS[2], 300) end
        if failures >= 5 then redis.call('DEL', KEYS[1]); return -1 end
        return 0
        """, Long.class);

    public MiniAppPhoneCodeService(StringRedisTemplate redis, Environment environment,
            @Value("${wechat.miniapp.phone-code-mode:disabled}") String mode) {
        this.redis = redis;
        this.environment = environment;
        this.mode = mode;
    }

    public boolean developmentEnabled() {
        var profiles = Arrays.asList(environment.getActiveProfiles());
        return "development".equals(mode) && (profiles.contains("local") || profiles.contains("test"))
                && !profiles.contains("prod") && !profiles.contains("production");
    }

    public Map<String, Object> config() {
        return Map.of("phoneLoginEnabled", developmentEnabled(), "developmentCodeMode", developmentEnabled());
    }

    public static String phone(String value) {
        String phone = value == null ? "" : value.trim();
        if (!phone.matches("1[3-9][0-9]{9}")) throw new ServiceException("请输入有效的11位手机号");
        return phone;
    }

    private void available() {
        if (!developmentEnabled()) throw new ServiceException("短信登录尚未开通，请联系管理员配置短信服务");
    }

    public Map<String, Object> issue(String value, String ip) {
        available();
        String phone = phone(value), prefix = "miniapp:phone:" + phone;
        String code = String.format("%06d", RANDOM.nextInt(1_000_000));
        Long result = redis.execute(ISSUE, List.of(prefix + ":code", prefix + ":cooldown", prefix + ":attempts",
                "miniapp:phone:ip:" + (ip == null ? "unknown" : ip)), code);
        if (Long.valueOf(-1).equals(result)) throw new ServiceException("请60秒后再获取验证码");
        if (!Long.valueOf(1).equals(result)) throw new ServiceException("获取验证码过于频繁，请稍后重试");
        // Only returned after the local/test AND mode checks above. No real SMS is sent.
        return Map.of("resendAfter", 60, "expiresIn", 300, "developmentCode", code);
    }

    public void verify(String value, String code) {
        available();
        String phone = phone(value);
        if (code == null || !code.matches("[0-9]{6}")) throw new ServiceException("请输入6位验证码");
        String prefix = "miniapp:phone:" + phone;
        Long result = redis.execute(VERIFY, List.of(prefix + ":code", prefix + ":attempts"), code);
        if (!Long.valueOf(1).equals(result)) throw new ServiceException("验证码错误、已失效或尝试次数过多，请重新获取");
    }
}
