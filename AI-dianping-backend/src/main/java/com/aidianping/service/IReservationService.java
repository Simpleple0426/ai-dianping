package com.aidianping.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.aidianping.dto.ReservationDTO;
import com.aidianping.dto.Result;
import com.aidianping.entity.Reservation;

/**
 * <p>
 *  服务类
 * </p>
 *
 */
public interface IReservationService extends IService<Reservation> {

    /**
     * 创建预约
     *
     * @param dto 预约信息
     * @return 预约结果
     */
    Result createReservation(ReservationDTO dto);
}
