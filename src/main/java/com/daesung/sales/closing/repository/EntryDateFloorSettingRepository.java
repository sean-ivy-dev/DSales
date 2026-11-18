package com.daesung.sales.closing.repository;

import com.daesung.sales.closing.entity.EntryDateFloorSetting;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EntryDateFloorSettingRepository
        extends JpaRepository<EntryDateFloorSetting, Long> {
}
