package com.codenear.butterfly.notify.alarm.domain;

import com.codenear.butterfly.member.domain.Member;
import com.codenear.butterfly.product.domain.Product;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Table(
        uniqueConstraints = {
                @UniqueConstraint(columnNames = {"member_id", "product_id"})
        }
)
@Entity
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Getter
public class Restock {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "member_id")
    private Member member;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "product_id")
    private Product product;

    private Boolean isNotified;

    private Restock(final Member member, final Product product) {
        this.member = member;
        this.product = product;
        this.isNotified = false;
    }

    public static Restock create(final Member member, final Product product) {
        Restock restock = new Restock(member, product);
        member.addRestock(restock);
        product.addRestock(restock);

        return restock;
    }

    public void sendNotification() {
        this.isNotified = true;
        this.member.removeRestock(this);
        this.product.removeRestock(this);
    }

    public void applyRestockNotification() {
        this.isNotified = false;
        this.member.addRestock(this);
        this.product.addRestock(this);
    }
}
