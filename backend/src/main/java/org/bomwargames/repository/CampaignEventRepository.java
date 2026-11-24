package org.bomwargames.repository;

import java.util.List;
import org.bomwargames.model.CampaignEvent;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CampaignEventRepository extends JpaRepository<CampaignEvent, Long> {
    List<CampaignEvent> findByCampaign_Id(Long campaignId);
    boolean existsByCampaign_Id(Long campaignId);
}
