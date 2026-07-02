package com.sales.sync.repository;

import com.sales.sync.model.ProcessedRequest;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ProcessedRequestRepository extends JpaRepository<ProcessedRequest, String> {
}
