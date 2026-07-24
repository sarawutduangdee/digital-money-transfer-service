package com.bank.transfer.scheduler;

import com.bank.transfer.domain.OutboxEvent;
import com.bank.transfer.domain.OutboxStatus;
import com.bank.transfer.repository.OutboxEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.jms.core.JmsTemplate;

@Slf4j
@Component
@RequiredArgsConstructor
public class OutboxScheduler {

    private final OutboxEventRepository outboxEventRepository;

    private final JmsTemplate jmsTemplate;

    private static final String TRANSFER_COMPLETED_QUEUE = "TRANSFER.COMPLETED";

    @Scheduled(fixedDelay = 5000)
    public void processOutboxEvents() {
        List<OutboxEvent> pendingEvents = outboxEventRepository.findByStatus(OutboxStatus.PENDING);

        if (pendingEvents.isEmpty()) {
            return;
        }

        log.info("Found {} pending outbox events. Processing...", pendingEvents.size());

        for (OutboxEvent event : pendingEvents) {
            try {
                log.info("Sending event Aggregate ID: {} to IBM MQ...", event.getAggregateId());

                // 📌 ส่ง Payload (JSON) เข้า IBM MQ
                jmsTemplate.convertAndSend(TRANSFER_COMPLETED_QUEUE, event.getPayload());

                // เมื่อส่งสำเร็จ อัปเดตสถานะเป็น PUBLISHED
                event.setStatus(OutboxStatus.PUBLISHED);
                event.setPublishedAt(LocalDateTime.now());

                outboxEventRepository.save(event);

                log.info("Successfully published outbox event ID: {} to MQ", event.getId());

            } catch (Exception e) {
                // ถ้า IBM MQ ล่ม หรือเน็ตหลุด จะตกลงมาที่ catch
                // สถานะจะยังคงเป็น PENDING เพื่อให้ Scheduler รอบถัดไปมาดึงไปลองส่งใหม่ (Retry)
                log.error("Failed to publish outbox event ID: {} to MQ", event.getId(), e);
            }
        }
    }
}
