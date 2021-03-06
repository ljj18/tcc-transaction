package com.ljj.tcc.sample.redpacket.domain.repository;


import org.mengyun.tcctransaction.sample.exception.InsufficientBalanceException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Repository;

import com.ljj.tcc.sample.redpacket.domain.entity.RedPacketAccount;
import com.ljj.tcc.sample.redpacket.infrastructure.dao.RedPacketAccountDao;

/**
 * Created by liangjinjing on 4/2/16.
 */
@Repository
public class RedPacketAccountRepository {

    @Autowired
    RedPacketAccountDao redPacketAccountDao;

    public RedPacketAccount findByUserId(long userId) {

        return redPacketAccountDao.findByUserId(userId);
    }

    public void save(RedPacketAccount redPacketAccount) {
        int effectCount = redPacketAccountDao.update(redPacketAccount);
        if (effectCount < 1) {
            throw new InsufficientBalanceException();
        }
    }
}
