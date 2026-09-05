package com.boothlock.boothlock_server.menu.domain;

import com.boothlock.boothlock_server.booth.domain.BoothEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

@Entity
@Table(name = "menu", uniqueConstraints = @UniqueConstraint(
        name = "uk_menu_booth_name",
        columnNames = {"booth_id", "name"}
))
public class MenuEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "booth_id", nullable = false)
    private BoothEntity booth;

    @Column(nullable = false, length = 50)
    private String name;

    @Column(nullable = false)
    private int price;

    @Column(name = "image_url", length = 500)
    private String imageUrl;

    @Column(length = 200)
    private String description;

    @Column(name = "sold_out", nullable = false)
    private boolean soldOut = false;

    @Column(nullable = false)
    private boolean visible = true;

    protected MenuEntity() {
    }

    public MenuEntity(BoothEntity booth, String name, int price, String imageUrl, String description, boolean visible) {
        this.booth = booth;
        this.name = name;
        this.price = price;
        this.imageUrl = imageUrl;
        this.description = description;
        this.visible = visible;
    }

    public Long getId() {
        return id;
    }

    public BoothEntity getBooth() {
        return booth;
    }

    public String getName() {
        return name;
    }

    public int getPrice() {
        return price;
    }

    public String getImageUrl() {
        return imageUrl;
    }

    public String getDescription() {
        return description;
    }

    public boolean isSoldOut() {
        return soldOut;
    }

    public boolean isVisible() {
        return visible;
    }

    public void updateName(String name) {
        this.name = name;
    }

    public void updatePrice(int price) {
        this.price = price;
    }

    public void updateImageUrl(String imageUrl) {
        this.imageUrl = imageUrl;
    }

    public void updateDescription(String description) {
        this.description = description;
    }

    public void updateSoldOut(boolean soldOut) {
        this.soldOut = soldOut;
    }

    public void updateVisible(boolean visible) {
        this.visible = visible;
    }
}
