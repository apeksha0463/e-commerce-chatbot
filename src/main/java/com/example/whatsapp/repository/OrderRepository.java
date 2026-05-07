package com.example.whatsapp.repository;

import com.example.whatsapp.model.Order;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * Spring Data MongoDB repository for WhatsApp orders.
 * All CRUD methods are auto-generated — no implementation needed.
 */
@Repository
public interface OrderRepository extends MongoRepository<Order, String> {

    /** Find all orders for a given phone number */
    List<Order> findByPhone(String phone);

    /** Find all orders for a given product */
    List<Order> findByProductId(String productId);
}
