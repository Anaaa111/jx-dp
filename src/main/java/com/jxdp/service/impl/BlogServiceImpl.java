package com.jxdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.jxdp.dto.Result;
import com.jxdp.dto.ScrollResult;
import com.jxdp.dto.UserDTO;
import com.jxdp.entity.Blog;
import com.jxdp.entity.Follow;
import com.jxdp.entity.User;
import com.jxdp.mapper.BlogMapper;
import com.jxdp.service.IBlogService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.jxdp.service.IFollowService;
import com.jxdp.service.IUserService;
import com.jxdp.utils.RedisConstants;
import com.jxdp.utils.SystemConstants;
import com.jxdp.utils.UserHolder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.ArrayList;
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
public class BlogServiceImpl extends ServiceImpl<BlogMapper, Blog> implements IBlogService {
    @Resource
    IUserService userService;
    @Resource
    StringRedisTemplate stringRedisTemplate;
    @Resource
    IFollowService followService;

    /**
     * 根据点赞量查询博客列表
     * @param current
     * @return
     */
    @Override
    public Result queryHotBlog(Integer current) {
        // 根据用户查询
        Page<Blog> page =this.query()
                .orderByDesc("liked")
                .page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        // 获取当前页数据
        List<Blog> records = page.getRecords();
        // 查询用户
        records.forEach(blog -> {
            this.getBlogUser(blog);
            this.isBlogLiked(blog);
        });
        return Result.ok(records);
    }

    @Override
    public Result queryBlogById(Long id) {
        Blog blog = this.getById(id);
        if (blog == null){
            Result.fail("笔记不存在");
        }
        // 获取用户信息
        this.getBlogUser(blog);
        // 判断该用户是否对这个博客点过赞，点过赞则高亮
        this.isBlogLiked(blog);
        return Result.ok(blog);
    }

    /**
     * 点赞和取消点赞
     * 1.同一个用户只能点赞一次，再次点击则取消点赞
     * 2.如果当前用户已经点赞，则点赞按钮高亮显示（前端已实现，判断字段Blog类的isLike属性）
     * @param id
     * @return
     */
    @Override
    public Result likeBlog(Long id) {
        /**
         * 判断用户是否点过赞
         * 1.可以使用set集合保存某一个博客有哪些用户点过赞
         * 2. 用户点赞时，则往set集合添加该用户id
         * 3. 用户取消点赞时，则删除set的集合中的用户id
         * 4. 判断用户是否点过赞只需要判断set集合中是否有该用户id
         */
        // 获取用户id
        Long userId = UserHolder.getUser().getId();
        // set集合的key
        String key = RedisConstants.BLOG_LIKED_KEY + id;
        // 判断该用户是否点过赞(改进：使用sortedset)
        // Boolean isLiked = stringRedisTemplate.opsForSet().isMember(key, userId.toString());
        Double score = stringRedisTemplate.opsForZSet().score(key, userId.toString());
        if (score == null){
            // 没有点过赞，点赞，点赞数加1
            boolean success = this.update().setSql("liked = liked + 1").eq("id", id).update();
            if (success){
                // 修改成功，将该用户添加到sortedset集合中
                // stringRedisTemplate.opsForSet().add(key, userId.toString());
                stringRedisTemplate.opsForZSet().add(key, userId.toString(), System.currentTimeMillis());
            }
        }else{
            // 点过赞,取消点赞，点赞数-1
            boolean success = this.update().setSql("liked = liked - 1").eq("id", id).update();
            if (success){
                // 修改成功，将该用户从sortedset集合中删除
                stringRedisTemplate.opsForZSet().remove(key, userId.toString());
            }
        }

        return Result.ok();
    }

    /**
     * 显示点赞列表，根据点赞的时间排序
     * @param id
     * @return
     */
    @Override
    public Result queryBlogLikes(Long id) {
        /**
         * 如何有序的显示这个博客的的点赞列表？
         * set集合是无序的，所以我们不能使用set
         * 改进：当点赞时存入用户id时不能使用set，而是需要使用sortedset集合，方便后续直接排序显示
         */
        String key = RedisConstants.BLOG_LIKED_KEY + id;
        // 根据score排序查询前五名点赞的用户id
        // score是时间戳，所以前五名就是最先点赞的用户id
        Set<String> top5 = stringRedisTemplate.opsForZSet().range(key, 0, 4);
        if (top5 == null){
            // 如果点赞数为0，则返回一个空列表
            Result.ok(Collections.emptyList());
        }
        // 获取点过赞的用户id列表
        List<Long> ids = top5.stream().map(Long::valueOf).collect(Collectors.toList());
        /**
         * 遇到的问题：明明查出来的id是按点赞时间排序的，但是查询数据库出来的用户就不是按点赞时间排序的了
         * 原因：in(,,)不是按照里面的值依次查询的，所以查出来的就不是按顺序的了，可以使用ORDER BY FIELD(id,ids)
         * 保证查出来的用户按照ids的顺序进行排序
         */

        // 根据id列表查询用户信息
        // 将ids转换成字符串
        String idStr = StrUtil.join(",", ids);
        List<User> userList = userService.query().in("id", ids).last("ORDER BY FIELD(id," + idStr + ")").list();
        // 不需要将用户信息全部返回，封装成UserDTO，然后返回
        List<UserDTO> userDTOList = userList.stream().map(user -> BeanUtil.copyProperties(user, UserDTO.class)).collect(Collectors.toList());
        return Result.ok(userDTOList);
    }

    @Override
    public Result queryBlogByUserId(Long userId, Integer current) {
        // 根据用户id查询它的所有博客
        Page<Blog> page = this.query().eq("user_id", userId).page(new Page<>(current, SystemConstants.DEFAULT_PAGE_SIZE));
        List<Blog> blogs = page.getRecords();
        if (blogs == null){
            return Result.ok(Collections.emptyList());
        }
        return Result.ok(blogs);
    }

    @Override
    public Result saveBlog(Blog blog) {
        // 获取登录用户
        UserDTO user = UserHolder.getUser();
        blog.setUserId(user.getId());
        // 保存探店博文
        boolean isSuccess = this.save(blog);
        if (!isSuccess){
            return Result.fail("新增笔记失败！");
        }
        // 3.查询笔记作者的所有粉丝 select * from tb_follow where follow_user_id = ?
        List<Follow> follows = followService.query().eq("follow_user_id", user.getId()).list();
        if (follows != null || !follows.isEmpty()){
            // 推送笔记id给所有的粉丝
            for (Follow follow : follows) {
                // 获取粉丝id
                Long userId = follow.getUserId();
                // 推送到粉丝的收件箱中(每个粉丝都有一个收件箱,以粉丝的id为key)
                String key = RedisConstants.FEED_KEY + userId;
                stringRedisTemplate.opsForZSet().add(key, blog.getId().toString(), System.currentTimeMillis());
            }
        }
        // 返回id
        return Result.ok(blog.getId());
    }
    /**
     * 查询该用户关注的用户所发的博客
     * @param max 分页滚动查询中的上一次查询的最小值
     * @param offset 偏移量
     * @return
     */
    @Override
    public Result queryBlogOfFollow(Long max, Integer offset) {
        // 1. 获取当前用户
        Long userId = UserHolder.getUser().getId();
        // 2. 根据用户id查询该用户的收件箱中是否有当前用户关注的用户发的博客信息
        String key = RedisConstants.FEED_KEY + userId;
        Set<ZSetOperations.TypedTuple<String>> typedTuples = stringRedisTemplate.opsForZSet().reverseRangeByScoreWithScores(key, 0, max, offset, 2);
        // 3. 非空判断
        if (typedTuples == null || typedTuples.isEmpty()){
            return Result.ok();
        }
        // 4.解析数据：blogId， minTime(本次查询的最小时间戳，以便当作下次查询的最大值)， offset
        List<Long> ids = new ArrayList<>(typedTuples.size());
        long minTime = 0;
        // 计数器，用于计算与最小时间戳相同的列目有多少个以便当作offset
        int os = 1;
        for (ZSetOperations.TypedTuple<String> typedTuple : typedTuples) {
            // 4.1 获取博客id
            ids.add(Long.valueOf(typedTuple.getValue()));
            // 获取最小时间戳(其实就是本次查询的最后一条的分数)
            long time = typedTuple.getScore().longValue();
            /**
             * 首先获取当前条目的时间戳，若该条目的时间戳与最小时间戳相等，则os加1
             * 若不相同，则意味着当前的time是当前时刻的最小时间戳，则计数器重置，重新计算当前最小时间戳的个数
             */
            if (time == minTime){
                os++;
            }else {
                minTime = time;
                os = 1;
            }
        }
        // 5.根据id查询blog(按照ids的顺序)
        String idStr = StrUtil.join(",", ids);
        List<Blog> blogs = query().in("id", ids).last("ORDER BY FIELD(id," + idStr + ")").list();
        // 查询blog相关的用户和当前用户对该blog是否点过赞
        for (Blog blog : blogs) {
            // 5.1.查询blog有关的用户
            getBlogUser(blog);
            // 5.2.查询blog是否被点赞
            isBlogLiked(blog);
        }
        // 6. 封装并返回
        ScrollResult r = new ScrollResult();
        r.setList(blogs);
        r.setMinTime(minTime);
        r.setOffset(os);
        return Result.ok(r);
    }

    /**
     * 根据用户ID查询用户，并将主要信息封装到blog对象中
     * 注意：Blog是引用型，所以传到的是地址，在方法体中修改也是通过地址修改
     * 通过地址修改是能够影响到外边传进来的Blog对象本身的
     */
    private void getBlogUser(Blog blog){
        Long userId = blog.getUserId();
        User user = userService.getById(userId);
        blog.setIcon(user.getIcon());
        blog.setName(user.getNickName());
    }
    private void isBlogLiked(Blog blog){
        // 判断该用户是否登录
        UserDTO user = UserHolder.getUser();
        if (user == null){
            // 未登录，无需显示是否点过赞
            return;
        }
        // 获取当前用户id
        Long userId = user.getId();
        // 根据博客id获取key
        String key = RedisConstants.BLOG_LIKED_KEY + blog.getId();
        // 判断该用户是否点过赞
        Double score = stringRedisTemplate.opsForZSet().score(key, userId.toString());
        blog.setIsLike(score != null);
    }
}
