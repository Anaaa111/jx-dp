package com.jxdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.lang.UUID;
import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.jxdp.dto.LoginFormDTO;
import com.jxdp.dto.Result;
import com.jxdp.dto.UserDTO;
import com.jxdp.entity.User;
import com.jxdp.mapper.UserMapper;
import com.jxdp.service.IUserService;
import com.jxdp.utils.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.connection.BitFieldSubCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.servlet.http.HttpSession;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;


/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
@Slf4j
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {
    /**
     * 限定了redis中的key和value都得时String类型
     */
    @Autowired
    StringRedisTemplate stringRedisTemplate;

    /**
     * 发送验证码
     * @param phone
     * @param session
     * @return
     */
    @Override
    public Result sendCode(String phone, HttpSession session) {
        // 校验手机号
        if (RegexUtils.isPhoneInvalid(phone)){
            // 无效的手机号
            return Result.fail("手机号格式错误!");
        }
        // 手机号符合，生成验证码
        String code = RandomUtil.randomNumbers(6);
        // 将验证码保存到session中
        // session.setAttribute("code", code);
        /**
         * 改进(解决session共享问题)：使用redis保存验证码和用户信息
         * 多台Tomcat并不共享session存储空间，当请求切换到不同的tomcat服务时导致数据丢失问题
         */
        stringRedisTemplate.opsForValue().set(RedisConstants.LOGIN_CODE_KEY + phone, code, RedisConstants.LOGIN_CODE_TTL, TimeUnit.MINUTES);
        // 发送验证码
        log.info("验证码code: {}", code);
        return Result.ok();
    }

    /**
     * 登录功能
     * @param loginForm
     * @param session
     * @return
     */
    @Override
    public Result login(LoginFormDTO loginForm, HttpSession session) {
        // 校验手机号
        String phone = loginForm.getPhone();
        if (RegexUtils.isPhoneInvalid(phone)){
            // 无效的手机号
            return Result.fail("手机号格式错误!");
        }
        // 校验验证码
        // String code = session.getAttribute("code").toString();
        /**
         * 修改：验证码应该从redis取出
         */
        String code = stringRedisTemplate.opsForValue().get(RedisConstants.LOGIN_CODE_KEY + phone);

        String loginCode = loginForm.getCode();
        if (loginCode == null  || RegexUtils.isCodeInvalid(loginCode) || !loginCode.equals(code)){
            // 无效的验证码
            return Result.fail("验证码错误！");
        }
        // 根据手机号查询用户
        User user = this.query().eq("phone", loginForm.getPhone()).one();
        if (user == null){
            // 表明用户不存在
            // 注册该用户
            user = new User();
            user.setPhone(loginForm.getPhone());
            user.setNickName(SystemConstants.USER_NICK_NAME_PREFIX + RandomUtil.randomString(10));
            user.setCreateTime(LocalDateTime.now());
            user.setUpdateTime(LocalDateTime.now());
            this.save(user);
        }
        // 该用户存在，登录成功，将用户信息保存到session中
        // 将用户敏感信息隐藏
        UserDTO userDTO = new UserDTO();
        BeanUtils.copyProperties(user, userDTO);
        /**
         * 将用户信息保存到redis中：key如何设计？
         * 可以使用UUID随机生成key，并将这个key返回给前端，前端每次请求都携带这个key，
         * 后端就可以通过这个key获取到用户信息
         * 用户信息应该使用hash存取，故我们需要将userDTO转换成hashMap
         */
        // session.setAttribute("user", userDTO);
        String token = UUID.randomUUID().toString(true);
        /**
         * 由于我们使用的是stringRedisTemplate，所以我们要保证key和value都得是String类型
         */
        Map<String, Object> userMap = BeanUtil.beanToMap(userDTO, new HashMap<>(), CopyOptions.create().
                setIgnoreNullValue(true).
                setFieldValueEditor((fieldName, fieldValue) -> fieldValue.toString()));
        stringRedisTemplate.opsForHash().putAll(RedisConstants.LOGIN_USER_KEY + token, userMap);
        // 设置该token的有效期30分钟
        /**
         * 用户登录状态30分钟过期明显不合理
         * 根据session，没有任何请求时，30分钟过期，每有一次请求都会刷新过期时间
         * 模仿session,设置一个刷新的拦截器，拦截所有请求，每有一次请求就刷新token的过期时间
         */
        stringRedisTemplate.expire(RedisConstants.LOGIN_USER_KEY + token, RedisConstants.LOGIN_USER_TTL, TimeUnit.MINUTES);
        return Result.ok(token);
    }

    @Override
    public Result queryUserById(Long userId) {
        User user = this.getById(userId);
        if (user == null){
            return Result.fail("该用户不存在！");
        }
        UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);
        return Result.ok(userDTO);
    }

    @Override
    public Result sign() {
        // 1. 获取当前登录用户
        Long userId = UserHolder.getUser().getId();
        // 2. 获取当前日期
        LocalDateTime now = LocalDateTime.now();
        // 2.1 获取当前日期的年和月
        String keySuffix = now.format(DateTimeFormatter.ofPattern(":yyyyMM"));
        // 3. 拼接key
        String key = RedisConstants.USER_SIGN_KEY + userId + keySuffix;
        // 获取本月是第几天，从而在相应的位置设置为1
        int dayOfMonth = now.getDayOfMonth();
        // 写入redis中bitMap中
        stringRedisTemplate.opsForValue().setBit(key, dayOfMonth - 1, true);
        return Result.ok();
    }
    /**
     * 连续签到天数统计
     */
    @Override
    public Result signCount() {
        // 1. 获取当前登录用户
        Long userId = UserHolder.getUser().getId();
        // 2. 获取当前日期
        LocalDateTime now = LocalDateTime.now();
        // 2.1 获取当前日期的年和月
        String keySuffix = now.format(DateTimeFormatter.ofPattern(":yyyyMM"));
        // 3. 拼接key
        String key = RedisConstants.USER_SIGN_KEY + userId + keySuffix;
        // 获取本月是第几天，从而在相应的位置设置为1
        int dayOfMonth = now.getDayOfMonth();
        // 5. 获取本月截至今天为止的所有签到记录，返回的是一个十进制数目
        List<Long> result = stringRedisTemplate.opsForValue().bitField(key, BitFieldSubCommands.create().get(BitFieldSubCommands.BitFieldType.unsigned(dayOfMonth)).valueAt(0));
        if (result == null || result.isEmpty()){
            // 没有任何签到结果
            return Result.ok(0);
        }
        // 这个就是签到结果了
        Long num = result.get(0);
        if (num == null || num == 0){
            // 没有任何签到结果
            return Result.ok(0);
        }
        // 循环遍历
        // 首先将今天的签到结果获取到，因为今天可能签到了或者没签到，但是今天的结果都不能影响到连续签到天数，因为今天还没有过完呢
        long daySign = num & 1;
        // 所以，我们应该需要从昨天开始遍历，我们将获取到的签到结果往右一位，然后再开始遍历
        num >>>=1;
        int count = 0;
        while (true){
            // 6.1.让这个数字与1做与运算，得到数字的最后一个bit位  // 判断这个bit位是否为0
            if ((num & 1) == 0){
                // 如果为0，说明未签到，结束
                break;
            }else {
                // 如果不为0，说明已签到，计数器+1
                count++;
            }
            num >>>=1;
        }
        // 最后在将今天的签到结果与前面的连续签到天数相加，获取最终的连续签到天数
        count = count + (int)daySign;
        return Result.ok(count);
    }
}
