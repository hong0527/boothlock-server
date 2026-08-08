package com.boothlock.boothlock_server;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "booth")
public class BoothEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 50)
    private String name;

    @Column(name = "bank_account", nullable = false, length = 100)
    private String bankAccount;

    @Column(name = "is_open", nullable = false)
    private boolean open = true;

    @Column(name = "operating_hours", length = 50)
    private String operatingHours;

    protected BoothEntity() {
    }

    public BoothEntity(String name, String bankAccount, String operatingHours) {
        this.name = name;
        this.bankAccount = bankAccount;
        this.operatingHours = operatingHours;
    }

    public Long getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getBankAccount() {
        return bankAccount;
    }

    public boolean isOpen() {
        return open;
    }

    public String getOperatingHours() {
        return operatingHours;
    }
}
