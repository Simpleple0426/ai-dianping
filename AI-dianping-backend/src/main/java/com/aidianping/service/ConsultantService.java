package com.aidianping.service;

import com.aidianping.tools.AiSkillTool;
import com.aidianping.tools.OrderTool;
import com.aidianping.tools.ShopTool;
import com.aidianping.tools.VoucherTool;
import com.aidianping.utils.ReservationTool;
import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.spring.AiService;
import dev.langchain4j.service.spring.AiServiceWiringMode;
import reactor.core.publisher.Flux;

@AiService(
        wiringMode = AiServiceWiringMode.EXPLICIT,
        chatModel = "openAiChatModel",
        streamingChatModel = "openAiStreamingChatModel",
        chatMemoryProvider = "chatMemoryProvider",
        tools = {"shopTool", "voucherTool", "orderTool", "reservationTool", "aiSkillTool"}
)
public interface ConsultantService {
    @SystemMessage(fromResource = "system.prompt")
    Flux<String> chat(@MemoryId String memoryId, @UserMessage String message);
}
