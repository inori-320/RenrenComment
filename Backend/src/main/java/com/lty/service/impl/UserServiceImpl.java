package com.lty.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.lang.UUID;
import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.lty.dto.LoginFormDTO;
import com.lty.dto.Result;
import com.lty.dto.UserDTO;
import com.lty.entity.User;
import com.lty.mapper.UserMapper;
import com.lty.service.IUserService;
import com.lty.utils.RedisConstants;
import com.lty.utils.RegexUtils;
import com.lty.utils.SystemConstants;
import com.lty.utils.UserHolder;
import jakarta.servlet.http.HttpSession;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.connection.BitFieldSubCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import static com.lty.utils.RedisConstants.*;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author lty
 * @since 2024-4-13
 */
@Service
@Slf4j
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {
    @Autowired
    private StringRedisTemplate stringRedisTemplate;
    @Autowired
    private UserMapper userMapper;

    public Result sendCode(String phone, HttpSession session) {
        // 校验手机号
        if(RegexUtils.isPhoneInvalid(phone)){
            // 如果不符合，返回错误信息
            return Result.fail("手机号格式错误！");
        }
        // 符合则生成验证码
        String code = RandomUtil.randomNumbers(6);
        // 将验证码存到Redis中，5分钟内有效
        stringRedisTemplate.opsForValue().set(LOGIN_CODE_KEY + phone, code, LOGIN_CODE_TTL, TimeUnit.MINUTES);
        log.debug("发送验证码成功：{}", code);
        return Result.ok();
    }

    public Result login(LoginFormDTO loginForm, HttpSession session) {
        String phone = loginForm.getPhone();
        if(RegexUtils.isPhoneInvalid(phone)){
            return Result.fail("手机号错误！");
        }
        // 校验验证码是否正确
        String code = stringRedisTemplate.opsForValue().get(LOGIN_CODE_KEY + phone);
        if(!loginForm.getCode().equals(code)){
            return Result.fail("验证码不正确！");
        }
        User user = query().eq("phone", phone).one();
        if(user == null){
            // 如果用户为空，就创建一个新用户
            user = createUserWithPhone(phone);
        }
        UserDTO userDTO = new UserDTO();
        BeanUtils.copyProperties(user, userDTO);
        // 保存用户信息到Redis中
        // 随机生成token，作为登录令牌
        String token = UUID.randomUUID().toString(true);
        // 将User对象转为Hash存储
        Map<String, Object> data = BeanUtil.beanToMap(userDTO, new HashMap<>(),
                CopyOptions.create()
                        .ignoreNullValue()
                        .setFieldValueEditor((fieldName, fieldValue) -> fieldValue.toString()));
        // 存储并返回token
        stringRedisTemplate.opsForHash().putAll(LOGIN_USER_KEY + token, data);
        // 给token设置有效期，防止Redis被占满
        stringRedisTemplate.expire(LOGIN_USER_KEY + token, LOGIN_USER_TTL, TimeUnit.MINUTES);
        return Result.ok(token);
    }

    @Override
    public Result sign() {
        Long userId = UserHolder.getUser().getId();
        LocalDateTime now = LocalDateTime.now();
        String keySuffix = now.format(DateTimeFormatter.ofPattern(":yyyyMM"));
        String key = USER_SIGN_KEY + userId + keySuffix;
        // 获取今天是本月的第几天
        int dayOfMonth = now.getDayOfMonth();
        // 写入Redis
        stringRedisTemplate.opsForValue().setBit(key, dayOfMonth - 1, true);
        return Result.ok();
    }

    @Override
    public Result signCount() {
        Long userId = UserHolder.getUser().getId();
        LocalDateTime now = LocalDateTime.now();
        String keySuffix = now.format(DateTimeFormatter.ofPattern(":yyyyMM"));
        String key = USER_SIGN_KEY + userId + keySuffix;
        int dayOfMonth = now.getDayOfMonth();
        // 获取本日截止到今天为止的签到记录
        List<Long> result = stringRedisTemplate.opsForValue()
                .bitField(key,
                        BitFieldSubCommands.create()
                                .get(BitFieldSubCommands.BitFieldType.unsigned(dayOfMonth))
                                .valueAt(0));
        if(result == null || result.isEmpty() || result.get(0) == null || result.get(0) == 0){
            return Result.ok(0);
        }
        Long num = result.get(0);
        int ans = 0;
        while(num > 0){
            if((num & 1) == 0){
                break;
            }
            ans ++;
            num >>>= 1;
        }
        return Result.ok(ans);
    }

    private User createUserWithPhone(String phone) {
        User user = new User();
        user.setPhone(phone);
        user.setNickName(SystemConstants.USER_NICK_NAME_PREFIX + RandomUtil.randomString(9));
        save(user);
        return user;
    }
}
