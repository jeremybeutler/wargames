package org.wargames.repository;

import org.wargames.model.GameSession;
import org.springframework.data.jpa.repository.JpaRepository;

public interface GameSessionRepository extends JpaRepository<GameSession, String> {}
