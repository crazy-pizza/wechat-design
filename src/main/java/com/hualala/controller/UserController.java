package com.hualala.controller;


import com.hualala.common.UserResolver;
import com.hualala.model.User;
import com.hualala.service.UserService;
import com.hualala.util.ResultUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.ResponseBody;

/**
 * <p>
 * 公众号的用户信息 前端控制器
 * </p>
 *
 * @author YuanChong
 * @since 2019-07-06
 */
@Controller
@RequestMapping("/user")
public class UserController {


    @Autowired
    private UserService userService;

    /**
     * 更新用户签名或二维码
     *
     * @param params
     * @param user
     * @return
     */
    @ResponseBody
    @RequestMapping("/passport/updateByID")
    public Object updateByID(@RequestBody User params, @UserResolver User user) {
        params.setUserid(user.getUserid());
        userService.updateById(params);
        return ResultUtils.success();
    }

}
