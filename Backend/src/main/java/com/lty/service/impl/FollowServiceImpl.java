package com.lty.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.lty.dto.Result;
import com.lty.dto.UserDTO;
import com.lty.entity.Follow;
import com.lty.entity.User;
import com.lty.mapper.FollowMapper;
import com.lty.service.IFollowService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.lty.utils.UserHolder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

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
            save(follow);
        } else {
            // 取关，删除数据
            followMapper.removeFollower(userId, followUserId);
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
}
