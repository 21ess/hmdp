package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.lang.UUID;
import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RegexUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import javax.servlet.http.HttpSession;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;
import static com.hmdp.utils.SystemConstants.USER_NICK_NAME_PREFIX;

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

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result sendCode(String phone, HttpSession session) {
        // 1. 校验手机号
        // 使用正则式实现, utils实现
        if ( RegexUtils.isPhoneInvalid(phone)) {
            return Result.fail("手机号格式错误");
        }
        // 2. 如果符合, 生成验证码
        String code = RandomUtil.randomNumbers(6);

        // 3. 保存到session
        // session.setAttribute("code", code);
        // 3.1 修改，将验证码保存在redis当中
        stringRedisTemplate.opsForValue().set(LOGIN_CODE_KEY + phone, code, LOGIN_CODE_TTL, TimeUnit.MINUTES);

        // 4. 发送验证码
        log.debug("模拟发送短信验证码, code={}", code);

        // 返回ok即可，Result，提前定义的结果类
        return Result.ok();
    }

    @Override
    public Result login(LoginFormDTO loginForm, HttpSession session) {
        // 1.验证手机号
        if ( RegexUtils.isPhoneInvalid(loginForm.getPhone())) {
            return Result.fail("手机号格式错误");
        }
        String phone = loginForm.getPhone();

        // 2.校验验证码
        // String cacheCode = (String)session.getAttribute("code");
        String cacheCode = (String) stringRedisTemplate.opsForValue().get(LOGIN_CODE_KEY + phone);

        String code = loginForm.getCode();
        if (code == null || !code.equals(cacheCode)) {
            return Result.fail("验证码错误");
        }

        // 3.根据手机号查询用户
        User user = query().eq("phone", phone).one();


        // 4.如果不存在，需要创建新用户
        if (user == null) {
            // 操作数据库，保存用户
            user = createUserWithPhone(loginForm.getPhone());
        }
        log.debug("当前登录的用户为, {}", user.toString());
        // session.setAttribute("user", BeanUtil.copyProperties(user, UserDTO.class));
        // 5.将用户保存到Redis中，利用hash格式存储
        // 5.1生成token，也就是key
        String token = UUID.randomUUID().toString(true);

        // 5.2将user转化为hash
        UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);
        Map<String, Object> userDTOMap = BeanUtil.beanToMap(userDTO,
                new HashMap<>(),
                CopyOptions.create()
                        .setIgnoreNullValue(true)
                        .setFieldValueEditor((k, v) -> String.valueOf(v))); // 将Long类型转化为String类型

        stringRedisTemplate.opsForHash().putAll(LOGIN_USER_KEY + token, userDTOMap);    // 推荐使用putALL，减少与服务器的交互(put)
        stringRedisTemplate.expire(LOGIN_USER_KEY + token, LOGIN_USER_TTL, TimeUnit.MINUTES);

        //5.3 返回当前的token，作为登录凭证

        return Result.ok(token);

    }

    private User createUserWithPhone(String phone) {
        User user =  new User()
                .setPhone(phone)
                .setNickName(USER_NICK_NAME_PREFIX + RandomUtil.randomString(10));
        save(user);
        return user;
    }


}
