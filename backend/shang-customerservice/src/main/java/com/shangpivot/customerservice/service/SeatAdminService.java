package com.shangpivot.customerservice.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.shangpivot.common.exception.BizException;
import com.shangpivot.common.result.ErrorCode;
import com.shangpivot.customerservice.entity.ChatSession;
import com.shangpivot.customerservice.entity.Seat;
import com.shangpivot.customerservice.mapper.ChatSessionMapper;
import com.shangpivot.customerservice.mapper.SeatMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** 坐席：状态维护 + 接入并发校验。 */
@Service
@RequiredArgsConstructor
public class SeatAdminService {

    private final SeatMapper seatMapper;
    private final ChatSessionMapper sessionMapper;

    public Seat requireByAdminUser(Long adminUserId) {
        Seat seat = seatMapper.selectOne(new LambdaQueryWrapper<Seat>()
                .eq(Seat::getAdminUserId, adminUserId).last("LIMIT 1"));
        if (seat == null) {
            throw new BizException(ErrorCode.FORBIDDEN.getCode(), "当前账号不是坐席");
        }
        return seat;
    }

    public List<Map<String, Object>> list() {
        return seatMapper.selectList(new LambdaQueryWrapper<Seat>().orderByAsc(Seat::getId))
                .stream().map(this::toVo).toList();
    }

    public Seat updateStatus(Long seatId, String status) {
        Seat seat = requireSeat(seatId);
        seat.setStatus(status);
        seatMapper.updateById(seat);
        return seat;
    }

    public Seat updateMaxConcurrent(Long seatId, int maxConcurrent) {
        if (maxConcurrent < 1 || maxConcurrent > 20) {
            throw new BizException(ErrorCode.BAD_REQUEST.getCode(), "最大并发需在 1~20 之间");
        }
        Seat seat = requireSeat(seatId);
        seat.setMaxConcurrent(maxConcurrent);
        seatMapper.updateById(seat);
        return seat;
    }

    /** 接入校验：在线 + 并发未满。 */
    public void checkCanClaim(Seat seat) {
        if (!Seat.STATUS_ONLINE.equals(seat.getStatus())) {
            throw new BizException(ErrorCode.CONFLICT.getCode(), "请先将坐席状态置为在线");
        }
        long ongoing = sessionMapper.selectCount(new LambdaQueryWrapper<ChatSession>()
                .eq(ChatSession::getSeatId, seat.getId())
                .eq(ChatSession::getStatus, ChatSession.STATUS_HUMAN_ONGOING));
        if (ongoing >= seat.getMaxConcurrent()) {
            throw new BizException(ErrorCode.CONFLICT.getCode(), "已达最大并发会话数");
        }
    }

    /** 排队会话乐观锁接入：仅 QUEUED 可成功。 */
    public boolean claimQueued(ChatSession session, Seat seat) {
        int updated = sessionMapper.update(null, new LambdaUpdateWrapper<ChatSession>()
                .eq(ChatSession::getId, session.getId())
                .eq(ChatSession::getStatus, ChatSession.STATUS_QUEUED)
                .set(ChatSession::getStatus, ChatSession.STATUS_HUMAN_ONGOING)
                .set(ChatSession::getSeatId, seat.getId())
                .set(ChatSession::getUpdatedAt, java.time.OffsetDateTime.now()));
        return updated == 1;
    }

    /** 转接：目标坐席并发校验 + 状态保持 HUMAN_ONGOING。 */
    public void transferTo(ChatSession session, Long targetSeatId) {
        Seat target = requireSeat(targetSeatId);
        checkCanClaim(target);
        session.setSeatId(targetSeatId);
        sessionMapper.updateById(session);
    }

    /** 当前并发数。 */
    public long ongoingCount(Long seatId) {
        return sessionMapper.selectCount(new LambdaQueryWrapper<ChatSession>()
                .eq(ChatSession::getSeatId, seatId)
                .eq(ChatSession::getStatus, ChatSession.STATUS_HUMAN_ONGOING));
    }

    public Map<String, Object> toVo(Seat seat) {
        Map<String, Object> m = new HashMap<>();
        m.put("id", seat.getId());
        m.put("adminUserId", seat.getAdminUserId());
        m.put("nickname", seat.getNickname());
        m.put("status", seat.getStatus());
        m.put("maxConcurrent", seat.getMaxConcurrent());
        m.put("ongoingCount", ongoingCount(seat.getId()));
        return m;
    }

    private Seat requireSeat(Long seatId) {
        Seat seat = seatMapper.selectById(seatId);
        if (seat == null || Boolean.TRUE.equals(seat.getDeleted())) {
            throw new BizException(ErrorCode.NOT_FOUND);
        }
        return seat;
    }
}
