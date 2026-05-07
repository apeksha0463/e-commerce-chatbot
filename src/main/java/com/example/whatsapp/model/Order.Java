package com.example.whatsapp.model;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import java.time.LocalDateTime;

/**
 * Represents a WhatsApp chatbot order stored in MongoDB Atlas.
 * Collection name: "whatsapp_orders"
 */
@Document(collection = "whatsapp_orders")
public class Order {

    @Id
    private String id;

    private String orderId;          // e.g. YOT-145920
    private String phone;            // customer WhatsApp number
    private String customerName;
    private String productId;
    private String productName;
    private String price;
    private String address;
    private String pincode;
    private String paymentMethod;    // COD | UPI | Online/Card
    private String status;           // PENDING | CONFIRMED | CANCELLED
    private LocalDateTime createdAt;

    public Order() {}

    public Order(String orderId, String phone, String customerName,
                 String productId, String productName, String price,
                 String address, String pincode, String paymentMethod) {
        this.orderId       = orderId;
        this.phone         = phone;
        this.customerName  = customerName;
        this.productId     = productId;
        this.productName   = productName;
        this.price         = price;
        this.address       = address;
        this.pincode       = pincode;
        this.paymentMethod = paymentMethod;
        this.status        = "PENDING";
        this.createdAt     = LocalDateTime.now();
    }

    // --- Getters & Setters ---

    public String getId()            { return id; }
    public String getOrderId()       { return orderId; }
    public String getPhone()         { return phone; }
    public String getCustomerName()  { return customerName; }
    public String getProductId()     { return productId; }
    public String getProductName()   { return productName; }
    public String getPrice()         { return price; }
    public String getAddress()       { return address; }
    public String getPincode()       { return pincode; }
    public String getPaymentMethod() { return paymentMethod; }
    public String getStatus()        { return status; }
    public LocalDateTime getCreatedAt() { return createdAt; }

    public void setId(String id)                    { this.id = id; }
    public void setOrderId(String orderId)          { this.orderId = orderId; }
    public void setPhone(String phone)              { this.phone = phone; }
    public void setCustomerName(String n)           { this.customerName = n; }
    public void setProductId(String productId)      { this.productId = productId; }
    public void setProductName(String productName)  { this.productName = productName; }
    public void setPrice(String price)              { this.price = price; }
    public void setAddress(String address)          { this.address = address; }
    public void setPincode(String pincode)          { this.pincode = pincode; }
    public void setPaymentMethod(String m)          { this.paymentMethod = m; }
    public void setStatus(String status)            { this.status = status; }
    public void setCreatedAt(LocalDateTime t)       { this.createdAt = t; }
}
