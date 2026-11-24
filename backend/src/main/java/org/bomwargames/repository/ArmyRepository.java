package org.bomwargames.repository;

import java.util.List;
import org.bomwargames.model.Army;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ArmyRepository extends JpaRepository<Army, Long> {
    List<Army> findByCampaign_Id(Long campaignId);
    boolean existsByCampaign_Id(Long campaignId);
}
