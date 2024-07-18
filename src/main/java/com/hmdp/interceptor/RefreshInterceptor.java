package com.hmdp.interceptor;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import com.hmdp.dto.UserDTO;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.UserHolder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.servlet.HandlerInterceptor;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 用于刷新用户登录信息缓存的全局拦截器
 */
public class RefreshInterceptor implements HandlerInterceptor {

    private StringRedisTemplate stringRedisTemplate;

    // 注意：因为拦截器并非是Spring生成的，需要使用构造器注入依赖

    public RefreshInterceptor(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    // 实现spring框架中的拦截
    // 第一版：基于session保存用户数据
    // 第二版：将用户数据保存在Redis当中
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        // 1.获取session
        // HttpSession session = request.getSession();
        // 1.获取请求头中的token
        String token = request.getHeader("authorization");
        if (StrUtil.isBlankIfStr(token)) {
            return true;
        }

        // 2.基于token，到redis中查找用户信息
        Map<Object, Object> userMap =
                stringRedisTemplate.opsForHash().entries(RedisConstants.LOGIN_USER_KEY + token);

        // 3.拦截user不存在的情况
        if (userMap.isEmpty()) {
            return true;
        }
        UserDTO userDTO = new UserDTO();
        userDTO = BeanUtil.fillBeanWithMap(userMap, userDTO, false);

        // 2.通过session获取用户信息
        // UserDTO user = (UserDTO)session.getAttribute("user");

        //

        // 3.如果为null，拦截
//        if (user == null) {
//            response.setStatus(401);
//            return false;
//        }

        // 4.放行，同时将用户的登录信息保存在ThreadLocal中

        // 4.将用户信息保存到ThreadLocal中
        UserHolder.saveUser(userDTO);

        // 5.刷新token的有效期30min
        stringRedisTemplate.expire(RedisConstants.LOGIN_USER_KEY + token, RedisConstants.LOGIN_USER_TTL, TimeUnit.MINUTES);

        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) throws Exception {
        UserHolder.removeUser();
    }
}
