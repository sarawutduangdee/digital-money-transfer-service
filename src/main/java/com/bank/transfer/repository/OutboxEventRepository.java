package com.bank.transfer.repository;

import com.bank.transfer.domain.OutboxEvent;
import com.bank.transfer.domain.OutboxStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, Long> {

    List<OutboxEvent> findByStatus(OutboxStatus status);
    List<OutboxEvent> findByStatusOrderByCreatedAtAsc(OutboxStatus status);
}
