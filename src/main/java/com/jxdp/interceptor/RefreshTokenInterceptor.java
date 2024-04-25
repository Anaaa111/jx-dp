package com.jxdp.interceptor;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import com.jxdp.dto.UserDTO;
import com.jxdp.utils.RedisConstants;
import com.jxdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Component
@Slf4j
public class RefreshTokenInterceptor implements HandlerInterceptor {
    @Autowired
    StringRedisTemplate stringRedisTemplate;
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        // HttpSession session = request.getSession();
        // UserDTO user = (UserDTO)session.getAttribute("user");
        /**
         * 改进：使用redis存取用户信息
         */
        String token = request.getHeader("authorization");
        // 判断是否token是否为空
        if (StrUtil.isBlank(token)) {
            return true;
        }
        // 通过token从redis获取用户
        Map<Object, Object> userMap = stringRedisTemplate.opsForHash().entries(RedisConstants.LOGIN_USER_KEY + token);
        // 判断用户是否存在
        if (userMap.isEmpty()){
            return true;
        }
        // 将获取的用户信息从map转换成UserDTO实体类
        UserDTO user = BeanUtil.fillBeanWithMap(userMap, new UserDTO(), false);
        // 用户存在，则将用户信息保存到LocalThread中
        UserHolder.saveUser(user);
        // 刷新token的过期时间
        stringRedisTemplate.expire(RedisConstants.LOGIN_USER_KEY + token, RedisConstants.LOGIN_USER_TTL, TimeUnit.MINUTES);
        return true;
    }
    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) throws Exception {
        // 移除用户信息
        UserHolder.removeUser();
    }
}
