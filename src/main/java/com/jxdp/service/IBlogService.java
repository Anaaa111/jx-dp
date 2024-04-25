package com.jxdp.service;

import com.jxdp.dto.Result;
import com.jxdp.entity.Blog;
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
    /**
     * 根据点赞量查询博客列表
     * @param current
     * @return
     */
    Result queryHotBlog(Integer current);

    /**
     * 根据id查询博客详情
     * @param id
     * @return
     */
    Result queryBlogById(Long id);

    /**
     * 点赞和取消点赞
     * @param id
     * @return
     */
    Result likeBlog(Long id);

    /**
     * 显示点赞列表，根据点赞的时间排序
     * @param id
     * @return
     */
    Result queryBlogLikes(Long id);

    /**
     * 根据id查询博主的探店笔记
     * @param userId
     * @param current
     * @return
     */
    Result queryBlogByUserId(Long userId, Integer current);

    Result saveBlog(Blog blog);
    /**
     * 查询该用户关注的用户所发的博客
     * @param max 分页滚动查询中的上一次查询的最小值
     * @param offset 偏移量
     * @return
     */
    Result queryBlogOfFollow(Long max, Integer offset);
}
