package com.cecil.cloudmonitor.repository;

import com.cecil.cloudmonitor.model.CloudResource;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CloudResourceRepository extends JpaRepository<CloudResource, Long> {
}
