package com.jxdp.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.jxdp.dto.LoginFormDTO;
import com.jxdp.dto.Result;
import com.jxdp.entity.User;

import javax.servlet.http.HttpSession;

/**
 * <p>
 *  服务类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
public interface IUserService extends IService<User> {
    /**
     * 发送验证码
     * @param phone
     * @param session
     * @return
     */
    Result sendCode(String phone, HttpSession session);

    /**
     * 登录功能
     * @param loginForm
     * @param session
     * @return
     */
    Result login(LoginFormDTO loginForm, HttpSession session);

    /**
     * 根据用户id查询信息
     * @param userId
     * @return
     */
    Result queryUserById(Long userId);

    Result sign();
    /**
     * 连续签到天数统计
     */
    Result signCount();
}
