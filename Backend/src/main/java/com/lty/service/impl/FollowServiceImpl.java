package com.lty.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.lty.dto.Result;
import com.lty.dto.UserDTO;
import com.lty.entity.Follow;
import com.lty.entity.User;
import com.lty.mapper.FollowMapper;
import com.lty.service.IFollowService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.lty.service.IUserService;
import com.lty.utils.RedisConstants;
import com.lty.utils.UserHolder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class FollowServiceImpl extends ServiceImpl<FollowMapper, Follow> implements IFollowService {
    @Autowired
    private FollowMapper followMapper;
    @Autowired
    private StringRedisTemplate stringRedisTemplate;
    @Autowired
    private IUserService userService;

    @Override
    public Result follow(Long followUserId, Boolean isFollow) {
        UserDTO user = UserHolder.getUser();
        if(user == null){
            return Result.fail("用户未登录！！");
        }
        Long userId = user.getId();
        if(isFollow){
            // 关注，新增数据
            Follow follow = new Follow();
            follow.setFollowUserId(followUserId);
            follow.setUserId(userId);
            boolean success = save(follow);
            // 把用户加入到redis中
            if(success){
                stringRedisTemplate.opsForSet().add(RedisConstants.FOLLOWS_KEY + userId, followUserId.toString());
            }
        } else {
            // 取关，删除数据
            followMapper.removeFollower(userId, followUserId);
            // 从关注列表中移除
            stringRedisTemplate.opsForSet().remove(RedisConstants.FOLLOWS_KEY + userId, followUserId.toString());
        }
        return Result.ok();
    }

    @Override
    public Result isFollow(Long followUserId) {
        UserDTO user = UserHolder.getUser();
        if(user == null){
            return Result.fail("用户未登录！！");
        }
        Long userId = user.getId();
        Long count = query().eq("user_id", userId).eq("follow_user_id", followUserId).count();
        return Result.ok(count > 0);
    }

    @Override
    public Result commonFollow(Long id) {
        UserDTO user = UserHolder.getUser();
        if(user == null){
            return Result.fail("用户未登录！！");
        }
        Long userId = user.getId();
        String key1 = RedisConstants.FOLLOWS_KEY + userId;
        String key2 = RedisConstants.FOLLOWS_KEY + id;
        // 求两个用户之间关注列表的交集
        Set<String> intersect = stringRedisTemplate.opsForSet().intersect(key1, key2);
        if(intersect == null || intersect.isEmpty()) return Result.ok(Collections.EMPTY_LIST);
        // 解析id集合
        List<Long> ids = intersect.stream().map(Long::valueOf).collect(Collectors.toList());
        // 查询用户
        List<UserDTO> users = userService.listByIds(ids)
                .stream()
                .map(i -> BeanUtil.copyProperties(i, UserDTO.class))
                .collect(Collectors.toList());
        return Result.ok(users);
    }
}
