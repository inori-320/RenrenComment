package com.lty.interceptor;

import cn.hutool.core.bean.BeanUtil;
import com.lty.dto.UserDTO;
import com.lty.utils.RedisConstants;
import com.lty.utils.UserHolder;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 拦截器类，在用户访问时，刷新redis中的token有效时长
 * @author lty
 */

public class RefreshTokenInterceptor implements HandlerInterceptor {
    private final StringRedisTemplate stringRedisTemplate;

    public RefreshTokenInterceptor(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler){
        // 获取请求头中的token
        String token = request.getHeader("authorization");
        if(token == null || token.isEmpty()){
            return true;
        }
        // 从redis中获取用户信息
        String key = RedisConstants.LOGIN_USER_KEY + token;
        Map<Object, Object> userData = stringRedisTemplate.opsForHash().entries(key);
        if(userData.isEmpty()){
            return true;
        }
        // 保存用户信息到ThreadLocal
        UserDTO user = BeanUtil.fillBeanWithMap(userData, new UserDTO(), false);
        UserHolder.saveUser(user);
        // 刷新用户token有效期
        stringRedisTemplate.expire(key, RedisConstants.LOGIN_USER_TTL, TimeUnit.MINUTES);
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex){
        UserHolder.removeUser();
    }
}
