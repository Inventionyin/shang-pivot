package com.shangpivot.identity.application;

import cn.hutool.jwt.JWT;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.shangpivot.common.exception.BizException;
import com.shangpivot.common.result.ErrorCode;
import com.shangpivot.common.util.JwtUtil;
import com.shangpivot.identity.dto.EmailLoginRequest;
import com.shangpivot.identity.entity.Customer;
import com.shangpivot.identity.mapper.CustomerMapper;
import com.shangpivot.identity.vo.CustomerLoginVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Set;

/**
 * C 端邮箱验证码注册/登录（V2 增量）：163 SMTP 真发码，随机 6 位、5 分钟有效、60 秒冷却。
 * 与短信登录（{@link CustomerAuthService}）同构：验证通过即自动注册并签发 web/h5 JWT。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CustomerEmailService {

    private static final String EMAIL_CODE_KEY = "email:code:";
    private static final String EMAIL_COOLDOWN_KEY = "email:cooldown:";
    private static final String EMAIL_FAIL_KEY = "email:fail:";
    private static final String EMAIL_IP_SEND_KEY = "email:send:ip:";
    private static final Duration CODE_TTL = Duration.ofMinutes(5);
    private static final Duration COOLDOWN_TTL = Duration.ofSeconds(60);
    private static final int MAX_FAILS = 5;
    private static final int IP_SEND_MAX_PER_HOUR = 10;
    private static final Set<String> CLIENT_TYPES = Set.of("web", "h5");
    private static final SecureRandom RANDOM = new SecureRandom();

    private final CustomerMapper customerMapper;
    private final StringRedisTemplate redisTemplate;
    private final JwtUtil jwtUtil;
    private final JavaMailSender mailSender;

    @Value("${spring.mail.username:}")
    private String fromAddress;

    public MapResult sendEmailCode(String email, String clientIp) {
        String normalized = normalizeEmail(email);
        String from = fromAddress == null ? "" : fromAddress.trim();
        if (from.isBlank()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "邮箱服务未配置，请联系管理员");
        }
        // P0 防滥用：单 IP 每小时最多 10 次发码
        if (clientIp != null && !clientIp.isBlank()) {
            String ipKey = EMAIL_IP_SEND_KEY + clientIp;
            Long ipCount = redisTemplate.opsForValue().increment(ipKey);
            if (ipCount != null && ipCount == 1L) {
                redisTemplate.expire(ipKey, Duration.ofHours(1));
            }
            if (ipCount != null && ipCount > IP_SEND_MAX_PER_HOUR) {
                throw new BizException(ErrorCode.BAD_REQUEST, "发送过于频繁，请稍后再试");
            }
        }
        String cooldownKey = EMAIL_COOLDOWN_KEY + normalized;
        if (Boolean.TRUE.equals(redisTemplate.hasKey(cooldownKey))) {
            Long ttl = redisTemplate.getExpire(cooldownKey);
            throw new BizException(ErrorCode.BAD_REQUEST, "发送过于频繁，请" + (ttl == null || ttl < 0 ? 60 : ttl) + "秒后再试");
        }
        String code = String.format("%06d", RANDOM.nextInt(1_000_000));
        try {
            SimpleMailMessage message = new SimpleMailMessage();
            message.setFrom(from);
            message.setTo(normalized);
            message.setSubject("尚品汇登录验证码");
            message.setText("您好！\n\n您正在登录尚品汇，本次验证码为：" + code
                    + "，5 分钟内有效。\n\n如非本人操作，请忽略本邮件。\n\n—— 尚品汇（选好物，享生活）");
            mailSender.send(message);
        } catch (Exception e) {
            log.warn("Email code send failed to={}: {}", maskEmail(normalized), e.getMessage());
            throw new BizException(ErrorCode.BAD_REQUEST, "验证码邮件发送失败，请稍后重试");
        }
        redisTemplate.opsForValue().set(EMAIL_CODE_KEY + normalized, code, CODE_TTL);
        redisTemplate.opsForValue().set(cooldownKey, "1", COOLDOWN_TTL);
        log.info("Email code sent to={}", maskEmail(normalized));
        return new MapResult(60);
    }

    public record MapResult(int cooldownSec) {
    }

    @Transactional
    public CustomerLoginVO loginByEmail(EmailLoginRequest req) {
        String clientType = req.getClientType() == null ? "" : req.getClientType().trim().toLowerCase();
        if (!CLIENT_TYPES.contains(clientType)) {
            throw new BizException(ErrorCode.BAD_REQUEST, "clientType 须为 web 或 h5");
        }
        req.setClientType(clientType);
        String email = normalizeEmail(req.getEmail());
        // P0 防爆破：验证码错误累计 5 次立即作废，需重新获取
        String failKey = EMAIL_FAIL_KEY + email;
        String cached = redisTemplate.opsForValue().get(EMAIL_CODE_KEY + email);
        if (cached != null && !cached.equals(req.getCode())) {
            Long fails = redisTemplate.opsForValue().increment(failKey);
            if (fails != null && fails == 1L) {
                redisTemplate.expire(failKey, CODE_TTL);
            }
            if (fails != null && fails >= MAX_FAILS) {
                redisTemplate.delete(EMAIL_CODE_KEY + email);
                redisTemplate.delete(failKey);
                throw new BizException(ErrorCode.LOGIN_FAILED, "错误次数过多，验证码已失效，请重新获取");
            }
            throw new BizException(ErrorCode.LOGIN_FAILED, "验证码错误或已过期");
        }
        if (cached == null) {
            throw new BizException(ErrorCode.LOGIN_FAILED, "验证码错误或已过期");
        }
        redisTemplate.delete(EMAIL_CODE_KEY + email);
        redisTemplate.delete(failKey);

        Customer customer = customerMapper.selectOne(new LambdaQueryWrapper<Customer>()
                .eq(Customer::getEmail, email));
        if (customer == null) {
            customer = new Customer();
            customer.setEmail(email);
            String nick = email.substring(0, email.indexOf('@'));
            customer.setNickname(nick.length() > 12 ? nick.substring(0, 12) : nick);
            customer.setGender(0);
            customer.setStatus("NORMAL");
            customerMapper.insert(customer);
            log.info("Customer auto-registered id={} email={}", customer.getId(), maskEmail(email));
        }
        if ("FROZEN".equals(customer.getStatus())) {
            throw new BizException(ErrorCode.CUSTOMER_FROZEN);
        }
        customer.setLastLoginAt(OffsetDateTime.now());
        customerMapper.updateById(customer);

        String access = jwtUtil.createAccessToken(customer.getId(), customerDisplayId(customer), clientType);
        String refresh = jwtUtil.createRefreshToken(customer.getId(), customerDisplayId(customer), clientType);
        return CustomerLoginVO.builder()
                .accessToken(access)
                .refreshToken(refresh)
                .expiresIn(jwtUtil.getAccessTtlSeconds())
                .customer(toInfo(customer))
                .build();
    }

    private String customerDisplayId(Customer c) {
        return c.getPhone() != null && !c.getPhone().isBlank() ? c.getPhone() : c.getEmail();
    }

    private CustomerLoginVO.CustomerInfo toInfo(Customer c) {
        return CustomerLoginVO.CustomerInfo.builder()
                .id(c.getId())
                .phone(c.getPhone() == null ? null : CustomerAuthService.maskPhone(c.getPhone()))
                .email(maskEmail(c.getEmail()))
                .nickname(c.getNickname())
                .avatar(c.getAvatar())
                .status(c.getStatus())
                .build();
    }

    static String normalizeEmail(String email) {
        if (email == null) {
            throw new BizException(ErrorCode.BAD_REQUEST, "请输入邮箱");
        }
        String e = email.trim().toLowerCase();
        if (!e.matches("^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$")) {
            throw new BizException(ErrorCode.BAD_REQUEST, "请输入正确的邮箱地址");
        }
        if (e.length() > 128) {
            throw new BizException(ErrorCode.BAD_REQUEST, "邮箱过长");
        }
        return e;
    }

    static String maskEmail(String email) {
        if (email == null || !email.contains("@")) {
            return email;
        }
        int at = email.indexOf('@');
        String local = email.substring(0, at);
        String head = local.substring(0, Math.min(2, local.length()));
        return head + "***" + email.substring(at);
    }
}
