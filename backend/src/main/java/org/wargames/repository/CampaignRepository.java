package org.wargames.repository;

import java.util.Optional;
import org.wargames.model.Campaign;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CampaignRepository extends JpaRepository<Campaign, Long> {
    Optional<Campaign> findBySlug(String slug);
}
