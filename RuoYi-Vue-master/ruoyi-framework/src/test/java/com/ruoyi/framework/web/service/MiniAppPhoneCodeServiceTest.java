package com.ruoyi.framework.web.service;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import com.ruoyi.common.exception.ServiceException;

class MiniAppPhoneCodeServiceTest {
    private MiniAppPhoneCodeService service(StringRedisTemplate redis, String mode, String... profiles) {
        var env = new StandardEnvironment(); env.setActiveProfiles(profiles);
        return new MiniAppPhoneCodeService(redis, env, mode);
    }

    @Test void productionNeverReturnsADevelopmentCodeEvenWithAccidentalConfiguration() {
        for (String[] profiles : List.of(new String[] {"prod"}, new String[] {"local", "prod"},
                new String[] {"production"}, new String[] {"test", "production"}, new String[] {})) {
            var s = service(null, "development", profiles);
            assertFalse(s.developmentEnabled());
            assertEquals(false, s.config().get("phoneLoginEnabled"));
            assertThrows(ServiceException.class, () -> s.issue("13900000044", "test"));
            assertThrows(ServiceException.class, () -> s.verify("13900000044", "123456"));
        }
        assertFalse(service(null, "disabled", "local").developmentEnabled());
        assertTrue(service(null, "development", "local").developmentEnabled());
    }

    @Test void malformedInputNeverReachesRedis() {
        var s = service(null, "development", "test");
        assertThrows(ServiceException.class, () -> s.issue("not-a-phone", "test"));
        assertThrows(ServiceException.class, () -> s.verify("13900000044", "x"));
        assertThrows(ServiceException.class, () -> s.verify(null, "123456"));
    }

    @Test @EnabledIfEnvironmentVariable(named="MINIAPP_REDIS_TEST", matches="true")
    void realRedisEnforcesCooldownSingleUseAndAttemptLimit() {
        var factory = new LettuceConnectionFactory("127.0.0.1", 6379); factory.afterPropertiesSet();
        var redis = new StringRedisTemplate(factory); var s = service(redis, "development", "test");
        String phone = "199" + String.format("%08d", new java.security.SecureRandom().nextInt(100_000_000));
        String prefix = "miniapp:phone:" + phone, ip = "isolated-test-" + java.util.UUID.randomUUID();
        try {
            var response = s.issue(phone, ip); String firstCode = (String) response.get("developmentCode");
            assertTrue(firstCode.matches("[0-9]{6}")); assertTrue(redis.getExpire(prefix + ":code") > 0);
            assertThrows(ServiceException.class, () -> s.issue(phone, ip));
            s.verify(phone, firstCode); assertThrows(ServiceException.class, () -> s.verify(phone, firstCode));
            redis.delete(prefix + ":cooldown"); String code = (String) s.issue(phone, ip).get("developmentCode");
            String wrong = code.equals("000000") ? "111111" : "000000";
            for (int n = 0; n < 5; n++) assertThrows(ServiceException.class, () -> s.verify(phone, wrong));
            String actual = code; assertThrows(ServiceException.class, () -> s.verify(phone, actual));
        } finally {
            redis.delete(List.of(prefix + ":code", prefix + ":cooldown", prefix + ":attempts", "miniapp:phone:ip:" + ip));
            factory.destroy();
        }
    }
}
