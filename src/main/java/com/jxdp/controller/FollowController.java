package com.jxdp.controller;


import com.jxdp.dto.Result;
import com.jxdp.service.IFollowService;
import org.springframework.web.bind.annotation.*;

import javax.annotation.Resource;

/**
 * <p>
 *  前端控制器
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@RestController
@RequestMapping("/follow")
public class FollowController {
    @Resource
    IFollowService followService;
    //关注或取消关注
    @PutMapping("/{id}/{isFollow}")
    public Result follow(@PathVariable("id") Long followUserId,
                         @PathVariable("isFollow") Boolean isFollow){
        return followService.follow(followUserId, isFollow);
    }
    //判断是否关注
    @GetMapping("/or/not/{id}")
    public Result isFollow(@PathVariable("id") Long followUserId) {
        return followService.isFollow(followUserId);
    }
    /**
     * 跟当前用户的共同关注
     */
    @GetMapping("common/{id}")
    public  Result followCommons(@PathVariable Long id){
        return followService.followCommons(id);
    }

}
