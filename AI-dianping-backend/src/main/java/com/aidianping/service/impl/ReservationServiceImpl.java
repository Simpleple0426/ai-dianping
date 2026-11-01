package com.aidianping.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.aidianping.dto.ReservationDTO;
import com.aidianping.dto.Result;
import com.aidianping.entity.Reservation;
import com.aidianping.entity.User;
import com.aidianping.mapper.ReservationMapper;
import com.aidianping.service.IReservationService;
import com.aidianping.service.IUserService;
import com.aidianping.utils.UserHolder;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 */
@Service
public class ReservationServiceImpl extends ServiceImpl<ReservationMapper, Reservation> implements IReservationService {

    private final IUserService userService;

    public ReservationServiceImpl(IUserService userService) {
        this.userService = userService;
    }

    @Override
    public Result createReservation(ReservationDTO dto) {
        Long userId = UserHolder.getUser() != null ? UserHolder.getUser().getId() : null;
        if (userId == null) {
            return Result.fail("用户未登录");
        }
        if (dto == null || dto.getShopId() == null || dto.getReservationTime() == null
                || dto.getNumPeople() == null || dto.getNumPeople() <= 0) {
            return Result.fail("参数不完整");
        }
        if (dto.getReservationTime().isBefore(LocalDateTime.now())) {
            return Result.fail("预约时间不能早于当前时间");
        }

        // 简单幂等/冲突校验：同一用户同一店铺同一时间段不能重复预约
        Long count = lambdaQuery()
                .eq(Reservation::getUserId, userId)
                .eq(Reservation::getShopId, dto.getShopId())
                .eq(Reservation::getReservationTime, dto.getReservationTime())
                .count();
        if (count != null && count > 0) {
            return Result.fail("该时间段已存在预约，请勿重复提交");
        }

        Reservation reservation = new Reservation();
        reservation.setUserId(userId);
        User user = userService.getById(userId);
        if (user != null) {
            reservation.setUserPhone(user.getPhone());
        }
        reservation.setShopId(dto.getShopId());
        reservation.setReservationTime(dto.getReservationTime());
        reservation.setNumPeople(dto.getNumPeople());
        reservation.setRemark(dto.getRemark());
        reservation.setStatus(0);
        reservation.setCreateTime(LocalDateTime.now());
        reservation.setUpdateTime(LocalDateTime.now());
        boolean saved = save(reservation);
        return saved ? Result.ok(reservation.getId()) : Result.fail("预约失败");
    }
}
