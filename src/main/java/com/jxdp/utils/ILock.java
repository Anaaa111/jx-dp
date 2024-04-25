package com.jxdp.utils;

public interface ILock {
    /**
     * 尝试获取锁
     * @param name 业务的名字(不同的业务，获取的锁应该也是需要不一样的)
     * @param timeoutSec 锁持有的超时时间，过期后自动释放
     * @return
     */
    boolean tryLock(String name, long timeoutSec);

    /**
     * 释放锁
     */
    void unLock(String name);
}
