package com.example.whatsapp.service;

import com.example.whatsapp.model.Order;
import com.example.whatsapp.repository.OrderRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Random;
import java.util.logging.Logger;

/**
 * Handles order creation.
 * Saves orders directly to MongoDB Atlas — no dependency on BGS backend token.
 */
@Service
public class OrderService {

    private static final Logger log = Logger.getLogger(OrderService.class.getName());

    @Autowired
    private OrderRepository orderRepository;

    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Creates and persists an order into MongoDB Atlas.
     *
     * @param phone         customer WhatsApp number
     * @param productId     selected product _id
     * @param productName   selected product name
     * @param price         final (discounted) price as a string
     * @param customerName  full name provided by user
     * @param address       delivery address
     * @param pincode       delivery pincode
     * @param paymentMethod COD | UPI | Online/Card
     * @return OrderResult with success flag and orderId
     */
    public OrderResult createOrder(String phone,
                                   String productId,
                                   String productName,
                                   String price,
                                   String customerName,
                                   String address,
                                   String pincode,
                                   String paymentMethod) {
        try {
            // Generate a unique YotMart order ID
            String orderId = "YOT-" + (100000 + new Random().nextInt(900000));

            Order order = new Order(
                    orderId, phone, customerName,
                    productId, productName, price,
                    address, pincode, paymentMethod
            );

            orderRepository.save(order);

            log.info("[OrderService] Order saved to MongoDB Atlas: " + orderId
                    + " | Phone: " + phone
                    + " | Product: " + productName
                    + " | Amount: ₹" + price
                    + " | Payment: " + paymentMethod);

            return OrderResult.success(orderId);

        } catch (Exception e) {
            log.severe("[OrderService] Failed to save order: " + e.getMessage());
            return OrderResult.failure(e.getMessage());
        }
    }

    // ── Result wrapper ────────────────────────────────────────────────────────

    public static class OrderResult {
        public final boolean success;
        public final String orderId;
        public final String errorMessage;

        private OrderResult(boolean success, String orderId, String errorMessage) {
            this.success      = success;
            this.orderId      = orderId;
            this.errorMessage = errorMessage;
        }

        public static OrderResult success(String orderId) {
            return new OrderResult(true, orderId, null);
        }

        public static OrderResult failure(String msg) {
            return new OrderResult(false, null, msg);
        }
    }
}
