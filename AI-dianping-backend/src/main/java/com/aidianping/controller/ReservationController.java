package com.aidianping.controller;

import com.aidianping.dto.ReservationDTO;
import com.aidianping.dto.Result;
import com.aidianping.entity.Reservation;
import com.aidianping.service.IReservationService;
import com.aidianping.utils.UserHolder;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/reservation")
@RequiredArgsConstructor
public class ReservationController {

    private final IReservationService reservationService;

    @PostMapping
    public Result create(@RequestBody ReservationDTO dto) {
        return reservationService.createReservation(dto);
    }

    @GetMapping("/{id}")
    public Result getById(@PathVariable("id") Long id) {
        Reservation reservation = reservationService.getById(id);
        return reservation == null ? Result.fail("预约不存在") : Result.ok(reservation);
    }

    @GetMapping("/my")
    public Result myList() {
        Long userId = UserHolder.getUser() != null ? UserHolder.getUser().getId() : null;
        List<Reservation> list = reservationService.lambdaQuery()
                .eq(userId != null, Reservation::getUserId, userId)
                .orderByDesc(Reservation::getCreateTime)
                .list();
        return Result.ok(list, (long) list.size());
    }
}
