package com.lty.mapper;

import com.lty.entity.Follow;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;

/**
 * <p>
 *  Mapper 接口
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Mapper
public interface FollowMapper extends BaseMapper<Follow> {
    void removeFollower(Long userId, Long followUserId);
}
