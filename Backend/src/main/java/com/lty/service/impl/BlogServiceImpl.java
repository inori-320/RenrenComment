package com.lty.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.lty.dto.Result;
import com.lty.dto.UserDTO;
import com.lty.entity.Blog;
import com.lty.entity.Follow;
import com.lty.entity.ScrollResult;
import com.lty.entity.User;
import com.lty.mapper.BlogMapper;
import com.lty.service.IBlogService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.lty.service.IFollowService;
import com.lty.service.IUserService;
import com.lty.utils.RedisConstants;
import com.lty.utils.SystemConstants;
import com.lty.utils.UserHolder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;

import java.util.*;
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
public class BlogServiceImpl extends ServiceImpl<BlogMapper, Blog> implements IBlogService {
    @Autowired
    private IUserService userService;
    @Autowired
    private StringRedisTemplate stringRedisTemplate;
    @Autowired
    private IFollowService followService;

    private void queryBlogUser(Blog blog) {
        Long userId = blog.getUserId();
        User user = userService.getById(userId);
        blog.setName(user.getNickName());
        blog.setIcon(user.getIcon());
    }

    private void isBlogLiked(Blog blog) {
        UserDTO userDTO = UserHolder.getUser();
        if(userDTO == null) return;
        Double score = stringRedisTemplate.opsForZSet().score(RedisConstants.BLOG_LIKED_KEY + blog.getId(), userDTO.getId().toString());
        blog.setIsLike(score != null);
    }

    @Override
    public Result queryBlogById(Long id) {
        Blog blog = getById(id);
        if(blog == null){
            return Result.fail("笔记不存在！");
        }
        queryBlogUser(blog);
        isBlogLiked(blog);
        return Result.ok(blog);
    }

    /**
     * 用户点赞逻辑：每个用户只能点赞一次，重复点赞视为取消
     * @param id
     */
    @Override
    public void likeBlog(Long id) {
        UserDTO userDTO = UserHolder.getUser();
        if(userDTO == null) return;
        Long userId = userDTO.getId();
        Double score = stringRedisTemplate.opsForZSet().score(RedisConstants.BLOG_LIKED_KEY + id, userId.toString());
        // 没赞过，可以点赞
        if(score == null){
            // 点赞数+1
            boolean isSuccess = update().setSql("liked = liked + 1").eq("id", id).update();
            if(isSuccess){
                stringRedisTemplate.opsForZSet().add(RedisConstants.BLOG_LIKED_KEY + id, userId.toString(), System.currentTimeMillis());
            }
        } else {
            // 否则取消点赞，点赞数-1
            boolean isSuccess = update().setSql("liked = liked - 1").eq("id", id).update();
            if(isSuccess){
                stringRedisTemplate.opsForZSet().remove(RedisConstants.BLOG_LIKED_KEY + id, userId.toString());
            }
        }
    }

    @Override
    public Result queryHotBlog(Integer current) {
        // 根据用户查询
        Page<Blog> page = query()
                .orderByDesc("liked")
                .page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        // 获取当前页数据
        List<Blog> records = page.getRecords();
        // 查询用户
        records.forEach(blog -> {
            this.queryBlogUser(blog);
            this.isBlogLiked(blog);
        });
        return Result.ok(records);
    }

    @Override
    public Result topLikes(Long id) {
        Set<String> top5 = stringRedisTemplate.opsForZSet().range(RedisConstants.BLOG_LIKED_KEY + id, 0, 4);
        if(top5 == null || top5.isEmpty()){
            return Result.ok(Collections.emptyList());
        }
        List<Long> ids = top5.stream().map(Long::valueOf).toList();
        String idStr = StrUtil.join(",", ids);
        List<UserDTO> users = userService.query().in("id", ids).last("ORDER BY FIELD(id, " + idStr + ")").list()
                .stream()
                .map(user -> BeanUtil.copyProperties(user, UserDTO.class))
                .collect(Collectors.toList());
        return Result.ok(users);
    }

    @Override
    public Result saveBlog(Blog blog) {
        // 获取登录用户
        UserDTO user = UserHolder.getUser();
        blog.setUserId(user.getId());
        // 保存探店博文
        boolean isSave = save(blog);
        if(!isSave){
            return Result.fail("笔记保存失败！");
        }
        // 查询笔记作者的所有id
        List<Follow> followers = followService.query().eq("follow_user_id", user.getId()).list();
        // 把笔记推送给所有粉丝
        followers.forEach(follow -> {
            // 获取所有的粉丝id
            Long userId = follow.getUserId();
            stringRedisTemplate.opsForZSet().add(RedisConstants.FEED_KEY + userId,
                    blog.getId().toString(),
                    System.currentTimeMillis());
        });
        // 返回id
        return Result.ok(blog.getId());
    }

    @Override
    public Result queryBlogOfFollow(Long max, Integer offset) {
        Long userId = UserHolder.getUser().getId();
        // 查询收件箱中的数据
        Set<ZSetOperations.TypedTuple<String>> typedTuples = stringRedisTemplate.opsForZSet()
                .reverseRangeByScoreWithScores(RedisConstants.FEED_KEY + userId, 0, max, offset, 3);
        if(typedTuples == null || typedTuples.isEmpty()){
            return Result.ok(Collections.EMPTY_LIST);
        }
        // 解析数据：blogId，minTime，offset
        List<Long> ids = new ArrayList<>(typedTuples.size());
        long minTime = 0;
        int os = 1;
        for (ZSetOperations.TypedTuple<String> tuple : typedTuples) {
            ids.add(Long.valueOf(tuple.getValue()));
            // 获取最小时间戳，并且计算最小时间戳的数量
            long time = tuple.getScore().longValue();
            if(time == minTime) os ++;
            else{
                minTime = time;
                os = 1;
            }
        }
        // 根据id批量查询blog
        String idStr = StrUtil.join(",", ids);
        List<Blog> blogs = query()
                .in("id", ids).last("ORDER BY FIELD(id, " + idStr + ")").list();

        blogs.forEach(blog -> {
            // 查询blog有关的用户
            queryBlogUser(blog);
            // 查询该blog是否被点过赞
            isBlogLiked(blog);
        });
        // 封装并返回
        ScrollResult scrollResult = new ScrollResult();
        scrollResult.setList(blogs);
        scrollResult.setOffset(os);
        scrollResult.setMinTime(minTime);
        return Result.ok(scrollResult);
    }
}
