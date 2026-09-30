package psia.g17.entity;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "genre", schema = "movies")
public class Genre extends PanacheEntityBase {

    @Id
    @Column(name = "genre_id")
    public Integer id;

    public String name;
}
