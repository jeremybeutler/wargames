package org.bomwargames.repository;

import java.util.List;
import org.bomwargames.model.Route;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RouteRepository extends JpaRepository<Route, Long> {
    List<Route> findByCampaign_Id(Long campaignId);
    boolean existsByCampaign_Id(Long campaignId);
}
