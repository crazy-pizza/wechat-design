package com.hualala.util;

import com.hualala.user.domain.User;

/**
 * @author YuanChong
 * @create 2019-06-01 13:22
 * @desc
 */
public class CurrentUser {
    private static ThreadLocal<User> currentUser = new ThreadLocal();


    public static User getUser() {
        return currentUser.get();
    }

    public static void setUser(User user) {
        currentUser.set(user);
    }

    public static void clear() {
        currentUser.remove();
    }


}
