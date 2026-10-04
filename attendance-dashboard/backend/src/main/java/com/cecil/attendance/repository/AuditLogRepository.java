package com.cecil.attendance.repository;

import com.cecil.attendance.model.AuditLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AuditLogRepository extends JpaRepository<AuditLog, Long> {

    /** Empty strings mean "no filter". */
    @Query(value = """
            select a from AuditLog a
            where (:username = '' or lower(a.username) like lower(concat('%', :username, '%')))
              and (:action = '' or a.action = :action)
            order by a.timestamp desc, a.id desc
            """,
            countQuery = """
            select count(a) from AuditLog a
            where (:username = '' or lower(a.username) like lower(concat('%', :username, '%')))
              and (:action = '' or a.action = :action)
            """)
    Page<AuditLog> search(@Param("username") String username, @Param("action") String action, Pageable pageable);
}
