package com.jxdp.controller;


import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.jxdp.dto.Result;
import com.jxdp.dto.UserDTO;
import com.jxdp.entity.Blog;
import com.jxdp.service.IBlogService;
import com.jxdp.utils.SystemConstants;
import com.jxdp.utils.UserHolder;
import org.springframework.web.bind.annotation.*;

import javax.annotation.Resource;
import java.util.List;

/**
 * <p>
 * 前端控制器
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@RestController
@RequestMapping("/blog")
public class BlogController {

    @Resource
    private IBlogService blogService;

    @PostMapping
    public Result saveBlog(@RequestBody Blog blog) {
        return blogService.saveBlog(blog);
    }

    @PutMapping("/like/{id}")
    public Result likeBlog(@PathVariable("id") Long id) {

        return blogService.likeBlog(id);
    }

    @GetMapping("/of/me")
    public Result queryMyBlog(@RequestParam(value = "current", defaultValue = "1") Integer current) {
        // 获取登录用户
        UserDTO user = UserHolder.getUser();
        // 根据用户查询
        Page<Blog> page = blogService.query()
                .eq("user_id", user.getId()).page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        // 获取当前页数据
        List<Blog> records = page.getRecords();
        return Result.ok(records);
    }

    @GetMapping("/hot")
    public Result queryHotBlog(@RequestParam(value = "current", defaultValue = "1") Integer current) {
        return blogService.queryHotBlog(current);
    }
    @GetMapping("/{id}")
    public Result queryBlogById(@PathVariable Long id){
        return blogService.queryBlogById(id);
    }
    /**
     * 显示点赞列表，根据点赞的时间排序
     */
    @GetMapping("likes/{id}")
    public Result queryBlogLikes(@PathVariable Long id){
        return blogService.queryBlogLikes(id);
    }

    /**
     * 根据用户id查询博主的探店笔记
     */
    @GetMapping("/of/user")
    public Result queryBlogByUserId(@RequestParam("id") Long userId,
                                    @RequestParam(value = "current", defaultValue = "1") Integer current){
        return blogService.queryBlogByUserId(userId, current);
    }

    /**
     * 查询该用户关注的用户所发的博客
     * @param max 分页滚动查询中的上一次查询的最小值
     * @param offset 偏移量
     * @return
     */
    @GetMapping("/of/follow")
    public Result queryBlogOfFollow(@RequestParam("lastId") Long max,
                                    @RequestParam(value = "offset", defaultValue = "0") Integer offset){

        return blogService.queryBlogOfFollow(max, offset);
    }

}
