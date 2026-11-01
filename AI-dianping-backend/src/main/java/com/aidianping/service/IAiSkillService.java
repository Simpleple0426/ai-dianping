package com.aidianping.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.aidianping.dto.AiSkillRunRequest;
import com.aidianping.dto.Result;
import com.aidianping.entity.AiSkill;

public interface IAiSkillService extends IService<AiSkill> {

    Result queryEnabledSkills();

    Result querySkillByCode(String code);

    Result runSkill(String code, AiSkillRunRequest request);

    Result queryMyRuns(String skillCode);
}
