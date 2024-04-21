package com.lty.service;

import com.lty.dto.Result;
import com.lty.entity.Blog;
import com.baomidou.mybatisplus.extension.service.IService;

/**
 * <p>
 *  服务类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
public interface IBlogService extends IService<Blog> {

    Result queryBlogById(Long id);

    void likeBlog(Long id);

    Result queryHotBlog(Integer current);

    Result topLikes(Long id);

    Result saveBlog(Blog blog);

    Result queryBlogOfFollow(Long max, Integer offset);
}
