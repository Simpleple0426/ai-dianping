package com.aidianping.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.aidianping.dto.LoginFormDTO;
import com.aidianping.dto.Result;
import com.aidianping.entity.User;
import jakarta.servlet.http.HttpSession;

/**
 * <p>
 *  服务类
 * </p>
 *
 */
public interface IUserService extends IService<User> {

    Result login(LoginFormDTO loginForm, HttpSession session);

    Result sendCode(String phone, HttpSession session);

    Result logout();

    Result sign();

    Result signCount();
}
