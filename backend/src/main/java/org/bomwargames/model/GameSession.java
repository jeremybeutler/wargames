package org.bomwargames.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "game_sessions")
public class GameSession {
    @Id
    @Column(length = 6)
    private String code;

    // Unbounded text (Postgres "text"), not @Lob: on Postgres a @Lob String becomes a
    // large object that fails to read outside a transaction.
    @JdbcTypeCode(SqlTypes.LONG32VARCHAR)
    @Column(nullable = false)
    private String state;

    protected GameSession() {}

    public GameSession(String code, String state) {
        this.code = code;
        this.state = state;
    }

    public String getCode() { return code; }
    public String getState() { return state; }
    public void setState(String state) { this.state = state; }
}
