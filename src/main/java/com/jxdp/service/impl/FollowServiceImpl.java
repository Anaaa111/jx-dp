package com.jxdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.BooleanUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.jxdp.dto.Result;
import com.jxdp.dto.UserDTO;
import com.jxdp.entity.Follow;
import com.jxdp.entity.User;
import com.jxdp.mapper.FollowMapper;
import com.jxdp.service.IFollowService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.jxdp.service.IUserService;
import com.jxdp.utils.RedisConstants;
import com.jxdp.utils.UserHolder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
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
    @Resource
    IUserService userService;
    @Resource
    StringRedisTemplate stringRedisTemplate;
    /**
     * 关注或取消关注
     * @param followUserId
     * @param isFollow
     * @return
     */
    @Override
    public Result follow(Long followUserId, Boolean isFollow) {
        Long userId = UserHolder.getUser().getId();
        // 判断你要关注的用户是否存在
        User user = userService.getById(followUserId);
        if (user == null){
            return Result.fail("你关注的用户不存在！");
        }
        // 判断你是要关注还是取消关注(isFollow为true是关注，为false为取关)
        String key = RedisConstants.FOLLOW_KEY + userId;
        if (isFollow){
            // 可能会有给一个用户被相同用户关注两次的情况
            Boolean isFollowDate = (Boolean)isFollow(followUserId).getData();
            if (BooleanUtil.isTrue(isFollowDate)){
                return Result.fail("同一个用户不能关注两次！");
            }
            // 关注，往tb_follow表中建立userId和followUserId的关系即可
            Follow follow = new Follow();
            follow.setUserId(userId);
            follow.setFollowUserId(followUserId);
            this.save(follow);
            /**
             * 由于后续需要显示共同关注列表，所以我们将该用户关注的用户id放到set集合中，方便用并集
             */
            stringRedisTemplate.opsForSet().add(key, followUserId.toString());
        }else {
            // 取关，将tb_follow中的userId和followUserId的关系删除即可
            this.remove(new QueryWrapper<Follow>().eq("user_id", userId).eq("follow_user_id", followUserId));
            stringRedisTemplate.opsForSet().remove(key, followUserId.toString());
        }
        return Result.ok();
    }

    /**
     * 判断是否关注
     * @param followUserId
     * @return
     */
    @Override
    public Result isFollow(Long followUserId) {
        Long userId = UserHolder.getUser().getId();
        Integer count = this.query().eq("user_id", userId).eq("follow_user_id", followUserId).count();
        return Result.ok(count > 0);
    }

    /**
     * 共同关注
     * @param id
     * @return
     */
    @Override
    public Result followCommons(Long id) {
        Long userId = UserHolder.getUser().getId();
        String key1 = RedisConstants.FOLLOW_KEY + userId;
        String key2 = RedisConstants.FOLLOW_KEY + id;
        Set<String> followCommonsUserIds = stringRedisTemplate.opsForSet().intersect(key1, key2);
        if (followCommonsUserIds == null || followCommonsUserIds.isEmpty()){
            // 无共同好友
            return Result.ok(Collections.emptyList());
        }
        List<Long> ids = followCommonsUserIds.stream().map(Long::valueOf).collect(Collectors.toList());
        // 查询共同好友的信息
        List<User> userList = userService.listByIds(ids);
        List<UserDTO> userDTOList = userList.stream().map(user -> BeanUtil.copyProperties(user, UserDTO.class)).collect(Collectors.toList());
        return Result.ok(userDTOList);
    }
}
